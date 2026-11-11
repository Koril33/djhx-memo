import contextlib,json,pathlib,shutil,socket,subprocess,tempfile,time,urllib.request,urllib.error,uuid,zipfile,io,signal,sys
bin=str(pathlib.Path(sys.argv[1]).resolve()) if len(sys.argv)>1 else str(pathlib.Path('../dist/memo-server-linux-amd64').resolve())
root=pathlib.Path(tempfile.mkdtemp(prefix='djhx-server-smoke-'));db=root/'db/memo.db';ep=root/'runtime/epoch';common=['--db',str(db),'--epoch-file',str(ep)]
with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
url=f'http://127.0.0.1:{port}'
def cli(command,*args):return subprocess.run([bin,command,*common,*args],text=True,capture_output=True,check=True)
def req(method,path,body=None,token=None):
 data=json.dumps(body,ensure_ascii=False).encode() if body is not None else None
 headers={'Content-Type':'application/json','X-Memo-Protocol':'2'}
 if token:headers['Authorization']='Bearer '+token
 try:
  with urllib.request.urlopen(urllib.request.Request(url+path,data=data,headers=headers,method=method),timeout=5) as r:return r.status,r.read()
 except urllib.error.HTTPError as e:return e.code,e.read()
def start():
 p=subprocess.Popen([bin,'serve',*common,'--dev-http','--listen',f'127.0.0.1:{port}'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 for _ in range(100):
  try:
   if req('GET','/healthz')[0]==200:return p
  except OSError:pass
  time.sleep(.02)
 raise RuntimeError('server startup failed')
def stop(p):p.send_signal(signal.SIGTERM);assert p.wait(timeout=10)==0
try:
 code=dict(line.split('=',1) for line in cli('invite-create').stdout.splitlines())['invite_code']
 process=start()
 a=dict(username='smoke_user',password='smoke test password',device_id=str(uuid.uuid4()),device_name='CLI Smoke',invite_code=code)
 status,raw=req('POST','/v1/auth/register',a);assert status==201,raw;session=json.loads(raw);epoch=session['epoch'];token=session['token']
 operation=dict(op_id=str(uuid.uuid4()),note_id=str(uuid.uuid4()),base_rev='0',content='# CLI 烟测\n正文🙂',deleted=False)
 payload=dict(epoch=epoch,ops=[operation]);status,raw=req('POST','/v1/sync/push',payload,token);assert status==200 and json.loads(raw)['results'][0]['status']=='accepted'
 status,again=req('POST','/v1/sync/push',payload,token);assert raw==again
 snapshot=root/'snap.db';cli('backup','--output',str(snapshot));cli('check','--db',str(snapshot))
 busy=subprocess.run([bin,'epoch-rotate','--epoch-file',str(ep)],text=True,capture_output=True);assert busy.returncode!=0
 status,raw=req('GET',f'/v1/sync/pull?epoch={epoch}&cursor=0',token=token);assert status==200 and json.loads(raw)['next_cursor']=='1'
 status,raw=req('GET','/v1/export',token=token);assert status==200
 with zipfile.ZipFile(io.BytesIO(raw)) as z:
  assert z.read(operation['note_id']+'.md').decode()==operation['content'];manifest=json.loads(z.read('manifest.json'));assert manifest['source_kind']=='server'
 operation2=dict(operation,op_id=str(uuid.uuid4()),base_rev='1',content='备份后修改');req('POST','/v1/sync/push',dict(epoch=epoch,ops=[operation2]),token)
 stop(process);process=None
 shutil.move(root/'db',root/'preserved-db');db.parent.mkdir();shutil.copy(snapshot,db);new_epoch=cli('epoch-rotate').stdout.strip();assert new_epoch!=epoch
 process=start();status,raw=req('POST','/v1/sync/push',payload,token);assert status==409 and json.loads(raw)['error']['code']=='SYNC_EPOCH_MISMATCH'
 status,raw=req('GET',f'/v1/sync/pull?epoch={new_epoch}&cursor=0',token=token);p=json.loads(raw);assert p['until']=='1' and p['changes'][0]['content']==operation['content']
 stop(process);process=None
 print('PASS CLI invitation initialization, HTTP register/push/pull/idempotency/ZIP, online backup, running epoch lock, stopped restore+epoch rotation, stale Push rejection; preserved backup content verified')
finally:
 if 'process' in locals() and process is not None:
  with contextlib.suppress(Exception):stop(process)
 shutil.rmtree(root)
