// Run only against an isolated debug WebView2 with remote debugging explicitly enabled.
// No browser packages or privileged test commands are added to the shipped application.
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';

const endpoint = process.env.DJHX_WEBVIEW_TEST_URL ?? 'http://127.0.0.1:9224';
assert.equal(new URL(endpoint).hostname, '127.0.0.1');
const targets = await (await fetch(`${endpoint}/json/list`)).json();
const target = targets.find((item) => item.url === 'http://tauri.localhost/');
assert.ok(target, 'start the isolated native debug application first');
const socket = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  socket.addEventListener('open', resolve, { once: true });
  socket.addEventListener('error', reject, { once: true });
});
let sequence = 0;
const pending = new Map();
socket.addEventListener('message', ({ data }) => {
  const message = JSON.parse(data);
  const task = pending.get(message.id);
  if (!task) return;
  pending.delete(message.id);
  clearTimeout(task.timeout);
  if (message.error) task.reject(new Error(JSON.stringify(message.error)));
  else task.resolve(message.result);
});
function send(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++sequence;
    const timeout = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`${method} timed out`));
    }, 10000);
    pending.set(id, { resolve, reject, timeout });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
async function evaluate(expression) {
  const result = await send('Runtime.evaluate', {
    expression,
    awaitPromise: true,
    returnByValue: true,
  });
  assert.ok(!result.exceptionDetails, result.exceptionDetails?.text);
  return result.result.value;
}
async function core(request) {
  const result = await evaluate(
    `window.__TAURI_INTERNALS__.invoke('core_invoke',{requestJson:${JSON.stringify(JSON.stringify(request))}}).then(JSON.parse)`,
  );
  assert.equal(result.ok, true, result.error?.code);
  return result.data;
}
async function until(expression) {
  for (let attempt = 0; attempt < 450; attempt++) {
    if (await evaluate(expression)) return;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`condition timed out: ${expression}`);
}
function click(label) {
  return evaluate(`document.querySelector('[aria-label=${JSON.stringify(label)}]').click()`);
}
async function clickText(text) {
  await until(
    `Array.from(document.querySelectorAll('button')).some(b=>b.textContent.trim()===${JSON.stringify(text)} && !b.disabled && b.getClientRects().length)`,
  );
  return evaluate(
    `Array.from(document.querySelectorAll('button')).find(b=>b.textContent.trim()===${JSON.stringify(text)} && b.getClientRects().length).click()`,
  );
}
let fixture;
let priorPreferences;
try {
  await until(`!!document.querySelector('.cm-content')`);
  const runtime = await evaluate(`window.__TAURI_INTERNALS__.invoke('runtime_info')`);
  assert.equal(runtime.version, '0.1.5');
  assert.equal(runtime.debug_http, true, 'use the isolated debug build');
  priorPreferences = await evaluate(
    `window.__TAURI_INTERNALS__.invoke('update_invoke',{action:'snapshot'}).then(JSON.parse)`,
  );
  assert.equal((await core({ action: 'state' })).profile.kind, 'local');
  assert.equal((await core({ action: 'list' })).length, 0, 'use a fresh isolated identifier');
  fixture = await core({
    action: 'create',
    request_id: crypto.randomUUID(),
    content: 'Windows updater synthetic note 中文😀',
  });
  if (!(await evaluate(`!!document.querySelector('.settings-tabs')`))) await click('账号与设置');
  await clickText('软件更新');
  await until(`!!document.querySelector('section[aria-label="软件更新"] input')`);
  await evaluate(
    `(()=>{const s=document.querySelector('section[aria-label="软件更新"] input');if(!s.checked)s.click();})()`,
  );
  await until(
    `window.__TAURI_INTERNALS__.invoke('update_invoke',{action:'snapshot'}).then(JSON.parse).then(s=>s.auto_check)`,
  );
  await evaluate(
    `(()=>{const s=document.querySelector('section[aria-label="软件更新"] select');s.value='notify';s.dispatchEvent(new Event('change',{bubbles:true}));})()`,
  );
  await until(
    `window.__TAURI_INTERNALS__.invoke('update_invoke',{action:'snapshot'}).then(JSON.parse).then(s=>s.download_policy==='notify')`,
  );
  await evaluate(
    `(()=>{const s=document.querySelector('section[aria-label="软件更新"] input');if(s.checked)s.click();})()`,
  );
  await until(
    `window.__TAURI_INTERNALS__.invoke('update_invoke',{action:'snapshot'}).then(JSON.parse).then(s=>!s.auto_check && s.download_policy==='notify')`,
  );
  await clickText('检查更新');

  await until(
    `window.__TAURI_INTERNALS__.invoke('update_invoke',{action:'snapshot'}).then(JSON.parse).then(s=>s.phase!=='checking')`,
  );
  const state = await evaluate(
    `window.__TAURI_INTERNALS__.invoke('update_invoke',{action:'snapshot'}).then(JSON.parse)`,
  );
  assert.equal((await core({ action: 'get', id: fixture.id })).content, fixture.content);
  assert.ok(['failed', 'up_to_date', 'available', 'ready'].includes(state.phase));
  assert.equal(await evaluate(`document.documentElement.scrollWidth<=innerWidth`), true);
  const image = await send('Page.captureScreenshot', { format: 'png' });
  const folder = new URL('../../.local/updater015/', import.meta.url);
  await mkdir(folder, { recursive: true });
  await writeFile(new URL('windows-updates.png', folder), Buffer.from(image.data, 'base64'));
  console.log(
    'PASS isolated WebView2: update settings, persisted preferences, manual check and local note preservation; result ' +
      state.phase +
      '/' +
      state.error,
  );
} finally {
  if (priorPreferences)
    await evaluate(
      `window.__TAURI_INTERNALS__.invoke('update_invoke',{action:'preferences',autoCheck:${priorPreferences.auto_check},downloadPolicy:${JSON.stringify(priorPreferences.download_policy)}})`,
    );
  if (fixture) {
    const n = await core({ action: 'get', id: fixture.id });
    const t = await core({
      action: 'set_deleted',
      id: n.id,
      expected_generation: n.generation,
      deleted: true,
    });
    await core({ action: 'purge', id: t.id, expected_generation: t.generation });
  }
  socket.close();
}
