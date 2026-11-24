// Exercise the real App/Help components in Chromium, with synthetic IPC only.
import { build } from 'vite';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('..', import.meta.url));
const browser =
  process.env.DJHX_TEST_BROWSER || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
async function cleanupProfile(directory) {
  const local = path.resolve(root, '../.local');
  if (
    path.dirname(directory) !== local ||
    !path.basename(directory).startsWith('settings-browser-')
  ) {
    throw new Error('unsafe profile cleanup');
  }
  await rm(directory, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
}
const probe = `
import {mockIPC} from '@tauri-apps/api/mocks';
import {mount,tick} from 'svelte';
import App from '/src/App.svelte';
import '/src/style.css';
const profile={id:'fixture',kind:'account',label:'Synthetic account',server_url:'https://example.invalid',username:'fixture',device_id:'device',read_only:false};
const devices=Array.from({length:18},(_,i)=>({id:String(i),name:'Synthetic device '+i,created_at:'2026-11-24T00:00:00Z',last_seen_at:null,expires_at:'2027-01-01T00:00:00Z',revoked_at:null,current:i===0}));
mockIPC((cmd,args)=>{
 if(cmd==='runtime_info')return {version:'0.2.3',tray_available:true,debug_http:false};
 if(cmd==='system_appearance')return false;
 if(cmd==='core_invoke'){
  const r=JSON.parse(args.requestJson);
  const data=r.action==='state'?{profile,profiles:[profile],sync:{state:'synced',pending:0,error:null,last_success:null},event_seq:'0',credential_persistent:true,local_import_count:0}:r.action==='devices'?devices:r.action==='list'?[]:null;
  return JSON.stringify({ok:true,data});
 }
 if(cmd==='update_invoke') return JSON.stringify({phase:'idle',platform:'linux',current_version:'0.2.3',download_source:'memo'});
 return null;
},{shouldMockEvents:true});
const errors=[];window.addEventListener('error',e=>errors.push(e.message));window.addEventListener('unhandledrejection',()=>errors.push('unhandled rejection'));
mount(App,{target:document.getElementById('app')});
const settle=async()=>{await tick();await new Promise(r=>setTimeout(r,40));};
const assert=(v,m)=>{if(!v)throw new Error(m);};
try{
 await settle();await settle();
 const settings=document.querySelector('.rail-bottom .rail-button');
 assert(settings,'settings entry');settings.click();await settle();
 const dialog=document.querySelector('.settings-dialog');assert(dialog,'dialog');
 const tabs=[...dialog.querySelectorAll('.settings-tabs button')];
 const body=dialog.querySelector('.dialog-content');const heights=[];
 for(const index of [0,1,2,3,4,5,3,0]){
  tabs[index].click();await settle();
  heights.push(dialog.getBoundingClientRect().height);
  assert(body.scrollTop===0,'tab scroll reset');
  if(index===3){assert(body.scrollHeight>body.clientHeight,'long devices scroll internally');body.scrollTop=150;}
 }
 assert(Math.max(...heights)-Math.min(...heights)<2,'dialog height varies: '+heights);
 const checkbox=dialog.querySelector('[aria-labelledby="lint-help-label"]');const checked=checkbox.checked;
 const help=dialog.querySelector('details.help');const summary=help.querySelector('summary');
 assert(!summary.closest('label,h3'),'invalid nested disclosure');
 const beforeHelpHeight=dialog.getBoundingClientRect().height;
 for(let i=0;i<20;i++){summary.click();await settle();assert(help.open,'help opens');assert(Math.abs(dialog.getBoundingClientRect().height-beforeHelpHeight)<2,'help grew dialog');assert(summary.getAttribute('aria-expanded')==='true','expanded state');assert(checkbox.checked===checked,'help changed preference');summary.focus();summary.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));await settle();assert(!help.open,'Escape closes help');assert(document.querySelector('.settings-dialog'),'Escape closed dialog');assert(document.activeElement===summary,'focus returns');}
 summary.click();await settle();body.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}));await settle();assert(!help.open,'outside click closes help');
 const trigger=help.querySelector('.help-trigger').getBoundingClientRect();assert(trigger.width>=36&&trigger.height>=36,'small target');
 assert(document.getElementById(checkbox.getAttribute('aria-describedby')),'missing description');
 // Check the focus trap includes the new summary when it is the last control.
 profile.kind='local';await new Promise(r=>setTimeout(r,1200));await settle();tabs[1].click();await settle();tabs[3].click();await settle();
 assert(!dialog.querySelector('.device-list'),'local profile fixture refreshed');
 const localSummary=dialog.querySelector('summary');assert(localSummary,'local device help');
 localSummary.focus();localSummary.dispatchEvent(new KeyboardEvent('keydown',{key:'Tab',bubbles:true}));await settle();
 assert(document.activeElement===dialog.querySelector('.settings-close'),'summary wraps focus');
 assert(errors.length===0,'runtime errors: '+errors.join(','));
 document.body.dataset.settingsResult='PASS';document.body.dataset.heights=heights.join(',');
}catch(e){document.body.dataset.settingsResult='FAIL';document.body.dataset.failure=e.message;}
`;
const bundle = await build({
  root,
  plugins: [
    {
      name: 'settings-fixture',
      resolveId(id) {
        if (id === 'virtual:settings-fixture') return id;
      },
      load(id) {
        if (id === 'virtual:settings-fixture') return probe;
      },
    },
  ],
  build: {
    write: false,
    rollupOptions: { input: 'virtual:settings-fixture', output: { entryFileNames: 'probe.js' } },
  },
});
const outputs = new Map(bundle.output.map((x) => ['/' + x.fileName, x]));
const css = bundle.output
  .filter((x) => x.fileName.endsWith('.css'))
  .map((x) => `<link rel="stylesheet" href="/${x.fileName}">`)
  .join('');
const server = createServer((req, res) => {
  if (req.url === '/__settings_regression') {
    res.setHeader('Content-Type', 'text/html');
    res.end(
      `<html><head>${css}</head><body><div id="app"></div><script type="module" src="/probe.js"></script></body></html>`,
    );
    return;
  }
  const item = outputs.get(req.url);
  if (!item) {
    res.writeHead(404);
    res.end();
    return;
  }
  res.setHeader(
    'Content-Type',
    item.fileName.endsWith('.css') ? 'text/css' : 'application/javascript',
  );
  res.end(item.type === 'chunk' ? item.code : item.source);
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
try {
  const url = `http://127.0.0.1:${server.address().port}/__settings_regression`;
  for (const size of ['800,560', '1280,900']) {
    const directory = await mkdtemp(path.join(root, '../.local/settings-browser-'));
    try {
      const args = [
        '--headless=new',
        '--no-first-run',
        '--disable-gpu',
        '--disable-extensions',
        `--user-data-dir=${directory}`,
        `--window-size=${size}`,
        '--lang=en-US',
        '--accept-lang=en-US',
        '--virtual-time-budget=15000',
        '--dump-dom',
        url,
      ];
      const output = await new Promise((resolve, reject) => {
        const child = spawn(browser, args);
        let stdout = '';
        let stderr = '';
        child.stdout.on('data', (b) => {
          stdout += b;
        });
        child.stderr.on('data', (b) => {
          stderr += b;
        });
        const timer = setTimeout(() => {
          child.kill();
          reject(new Error('browser timeout'));
        }, 45000);
        child.on('error', reject);
        child.on('close', (code) => {
          clearTimeout(timer);
          if (code === 0) resolve(stdout);
          else reject(new Error('browser exit ' + code + ' ' + stderr.slice(-300)));
        });
      });
      await writeFile(path.join(root, '../.local/settings-last-dom.html'), output);
      if (!output.includes('data-settings-result="PASS"'))
        throw new Error(
          size + ': ' + (output.match(/data-failure="([^"]*)"/)?.[1] || 'probe did not complete'),
        );
      console.log(
        'PASS real settings components: ' +
          size +
          '; long devices, stable height, repeated info/Escape, preference isolation, focus, accessibility',
      );
    } finally {
      await cleanupProfile(directory);
    }
  }
} catch (error) {
  console.error(error);
  process.exitCode = 1;
} finally {
  await new Promise((r) => server.close(r));
}
process.exit(process.exitCode || 0);
