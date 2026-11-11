// Run only against an isolated debug WebView2 with remote debugging explicitly enabled.
// No browser packages or privileged test commands are added to the shipped application.
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';

const endpoint = process.env.DJHX_WEBVIEW_TEST_URL ?? 'http://127.0.0.1:9223';
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
  for (let attempt = 0; attempt < 100; attempt++) {
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
const body = `Windows 0.1.4 验证 ${Date.now()}`;
try {
  await until(`!!document.querySelector('.cm-content')`);
  const runtime = await evaluate(`window.__TAURI_INTERNALS__.invoke('runtime_info')`);
  assert.equal(runtime.version, '0.1.4');
  assert.equal(runtime.debug_http, true);
  assert.equal((await core({ action: 'state' })).profile.kind, 'local');
  assert.equal(
    (await core({ action: 'list' })).length,
    0,
    'use a fresh isolated application identifier',
  );
  await click('新建笔记');
  await until(`!!document.querySelector('.cm-content[contenteditable="true"]')`);
  await evaluate(`document.querySelector('.cm-content').focus()`);
  await send('Input.insertText', { text: body });
  await until(`document.querySelector('.note-card-title')?.textContent===${JSON.stringify(body)}`);
  fixture = (await core({ action: 'list' }))[0];
  await new Promise((resolve) => setTimeout(resolve, 650));
  await send('Input.insertText', { text: ' additional input' });
  await until(
    `document.querySelector('.note-card-title')?.textContent.endsWith('additional input')`,
  );
  const before = await evaluate(
    `document.querySelector('.workspace').getBoundingClientRect().width`,
  );
  await evaluate(`void(window.__validationEditor=document.querySelector('.cm-content'))`);
  await click('隐藏笔记列表');
  const after = await evaluate(
    `document.querySelector('.workspace').getBoundingClientRect().width`,
  );
  assert.ok(after - before >= 230, 'editor must gain the list column width');
  assert.equal(
    await evaluate(`document.querySelector('.cm-content')===window.__validationEditor`),
    true,
  );
  await evaluate(`document.querySelector('.cm-content').focus()`);
  await send('Input.dispatchKeyEvent', {
    type: 'keyDown',
    key: 'z',
    code: 'KeyZ',
    windowsVirtualKeyCode: 90,
    modifiers: 2,
  });
  await send('Input.dispatchKeyEvent', {
    type: 'keyUp',
    key: 'z',
    code: 'KeyZ',
    windowsVirtualKeyCode: 90,
    modifiers: 0,
  });
  await until(`document.querySelector('.cm-content').textContent===${JSON.stringify(body)}`);
  for (const mode of ['预览', '对照', '源码']) {
    await click(mode);
    assert.equal(
      await evaluate(`document.querySelector('.cm-content')===window.__validationEditor`),
      true,
    );
  }
  await send('Emulation.setDeviceMetricsOverride', {
    width: 800,
    height: 600,
    deviceScaleFactor: 1,
    mobile: false,
  });
  assert.equal(await evaluate(`document.documentElement.scrollWidth<=window.innerWidth`), true);
  await click('显示笔记列表');
  assert.equal(await evaluate(`document.documentElement.scrollWidth<=window.innerWidth`), true);
  await click('隐藏笔记列表');
  await send('Emulation.clearDeviceMetricsOverride');
  const collapsed = await send('Page.captureScreenshot', { format: 'png' });
  const screenshots = new URL('../../.local/version014/', import.meta.url);
  await mkdir(screenshots, { recursive: true });
  await writeFile(
    new URL('windows-collapsed.png', screenshots),
    Buffer.from(collapsed.data, 'base64'),
  );
  await send('Page.reload');
  await until(`!!document.querySelector('[aria-label="显示笔记列表"]')`);
  await click('显示笔记列表');
  await until(`!!document.querySelector('.note-card')`);
  await evaluate(`document.querySelector('.note-card').click()`);
  await until(`document.querySelector('.cm-content').textContent===${JSON.stringify(body)}`);
  await click('笔记操作');
  await clickText('移到回收站');
  await until(`document.querySelector('.note-column').textContent.includes('暂无笔记')`);
  await click('回收站');
  await until(`!!document.querySelector('.note-card')`);
  await evaluate(`document.querySelector('.note-card').click()`);
  await until(`document.querySelector('.info-strip.warning-strip')?.textContent.includes('恢复')`);
  await click('笔记操作');
  await clickText('彻底删除');
  await until(`!!document.querySelector('#purge-heading')`);
  await clickText('取消');
  assert.equal((await core({ action: 'get', id: fixture.id })).purge_state, 'none');
  await click('笔记操作');
  await clickText('彻底删除');
  await until(`!!document.querySelector('#purge-heading')`);
  await evaluate(`document.querySelector('[aria-labelledby="purge-heading"] .danger').click()`);
  await until(`!document.querySelector('#purge-heading') && !document.querySelector('.note-card')`);
  const removed = await core({ action: 'get', id: fixture.id });
  assert.equal(removed.purge_state, 'purged');
  assert.equal(removed.content, '');
  assert.equal((await core({ action: 'list', deleted: true })).length, 0);
  const screenshot = await send('Page.captureScreenshot', { format: 'png' });
  const folder = new URL('../../.local/version014/', import.meta.url);
  await mkdir(folder, { recursive: true });
  await writeFile(new URL('windows-webview.png', folder), Buffer.from(screenshot.data, 'base64'));
  console.log(
    `PASS native WebView + SQLite: list width ${before}->${after}, editor identity/undo/modes, persisted collapse, trash confirmation/cancel/permanent deletion`,
  );
} finally {
  await send('Emulation.clearDeviceMetricsOverride');
  if (fixture) {
    const note = await core({ action: 'get', id: fixture.id });
    if (note.purge_state !== 'purged') {
      const trashed = note.deleted
        ? note
        : await core({
            action: 'set_deleted',
            id: note.id,
            expected_generation: note.generation,
            deleted: true,
          });
      await core({ action: 'purge', id: note.id, expected_generation: trashed.generation });
    }
  }
  socket.close();
}
