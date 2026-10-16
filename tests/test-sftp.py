#!/usr/bin/env python3
"""Actual JSch/Paramiko SFTP exchange against an ephemeral localhost-only server."""
import argparse,errno,json,os,socket,subprocess,sys,tempfile,threading
from pathlib import Path
import paramiko
p=argparse.ArgumentParser();p.add_argument('--json-jar',required=True,type=Path);p.add_argument('--desktop',type=Path);a=p.parse_args()
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(a.desktop or ROOT.parent/'chaosbox-desktop'))
import core
from chaos_sync import Store,atomic
from sync_transport import sftp_exchange,sftp_connection,valid_manifest

operation_reads=[]
media_reads=[]
fail_media_rename=False
fail_op_rename=False
deny_extra_channels=False
operation_upload_channels=set()
operation_download_channels=set()

class Auth(paramiko.ServerInterface):
    def __init__(self,key):self.key=key;self.channels=0
    def check_auth_publickey(self,user,key):return paramiko.AUTH_SUCCESSFUL if user=='tester' and key==self.key else paramiko.AUTH_FAILED
    def get_allowed_auths(self,user):return 'publickey'
    def check_channel_request(self,kind,channel):
        if kind!='session' or (deny_extra_channels and self.channels):return paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED
        self.channels+=1;return paramiko.OPEN_SUCCEEDED
class Server(paramiko.SFTPServerInterface):
    def __init__(self,server,*args,root,**kwargs):super().__init__(server,*args,**kwargs);self.root=root
    def path(self,path):
        result=(self.root/path.lstrip('/')).resolve()
        if not result.is_relative_to(self.root):raise PermissionError(errno.EACCES,'outside root')
        return result
    def canonicalize(self,path):return '/'+str(self.path(path).relative_to(self.root)).replace('\\','/').replace('/.','') if path not in ('.','/') else '/'
    def stat(self,path):
        try:return paramiko.SFTPAttributes.from_stat(self.path(path).stat())
        except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)
    lstat=stat
    def list_folder(self,path):
        try:
            result=[]
            for file in self.path(path).iterdir():
                attrs=paramiko.SFTPAttributes.from_stat(file.stat());attrs.filename=file.name;result.append(attrs)
            return result
        except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)
    def open(self,path,flags,attr):
        if path.endswith('.op') and not flags&(os.O_WRONLY|os.O_RDWR):
            operation_reads.append(path);operation_download_channels.add(id(self))
        if '/JPG/' in path and not flags&(os.O_WRONLY|os.O_RDWR):media_reads.append(path)
        try:
            fd=os.open(self.path(path),flags,0o600);mode='r+b' if flags&os.O_RDWR else 'wb' if flags&os.O_WRONLY else 'rb'
            stream=os.fdopen(fd,mode);handle=paramiko.SFTPHandle(flags);handle.readfile=stream;handle.writefile=stream;return handle
        except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)
    def mkdir(self,path,attr):return self.change(lambda:self.path(path).mkdir())
    def rmdir(self,path):return self.change(lambda:self.path(path).rmdir())
    def remove(self,path):return self.change(lambda:self.path(path).unlink())
    def rename(self,old,new):
        if new.endswith('.op'):
            operation_upload_channels.add(id(self))
            if fail_op_rename:return paramiko.SFTP_PERMISSION_DENIED
        if fail_media_rename and '/JPG/' in new:return paramiko.SFTP_PERMISSION_DENIED
        return self.change(lambda:self.path(old).rename(self.path(new)))
    def change(self,action):
        try:action();return paramiko.SFTP_OK
        except OSError as e:return paramiko.SFTPServer.convert_errno(e.errno)

with tempfile.TemporaryDirectory(prefix='chaosbox-local-sftp-') as temporary:
    folder=Path(temporary);server_root=folder/'server';server_root.mkdir()
    key=paramiko.RSAKey.generate(2048);host=paramiko.RSAKey.generate(2048);keypath=folder/'key';key.write_private_key_file(str(keypath))
    listener=socket.socket();listener.bind(('127.0.0.1',0));listener.listen();listener.settimeout(.2);port=listener.getsockname()[1]
    hosts=folder/'known_hosts';hosts.write_text(f'[127.0.0.1]:{port} ssh-rsa {host.get_base64()}\n')
    stop=threading.Event();connections=[]
    def serve():
        while not stop.is_set():
            try:connection,_=listener.accept()
            except socket.timeout:continue
            transport=paramiko.Transport(connection);connections.append(transport);transport.add_server_key(host)
            transport.set_subsystem_handler('sftp',paramiko.SFTPServer,Server,root=server_root)
            transport.start_server(server=Auth(key))
    thread=threading.Thread(target=serve,daemon=True);thread.start()
    classes=ROOT/'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
    android=ROOT/'.tooling/android-sdk/platforms/android-35/android.jar'
    jsch=next((ROOT/'.tooling/gradle-user-home/caches').rglob('jsch-2.28.0.jar'))
    classpath=f'{classes}:{a.json_jar}:{android}:{jsch}'
    subprocess.run(['javac','-cp',classpath,'-d',str(folder),str(ROOT/'tests/SftpSyncTest.java'),str(ROOT/'tests/SyncDestinationTest.java')],check=True)
    first,second=folder/'android',folder/'desktop'
    atomic(first/'TXT/box.json','[{"device":"one","comment":"SFTP Grüße 東京"}]')
    atomic(first/'JPG/SCHRAUBEN/Grüße photo.jpg','{"comment":"photo"}')
    store=Store(second/'JPG',second/'TXT',reader=lambda path:json.loads(path.read_text()),writer=lambda path,record:atomic(path,json.dumps(record)))
    def java():
        result=subprocess.check_output(['java','-cp',f'{folder}:{classpath}','local.sshcopy.SftpSyncTest',str(first),str(port),str(keypath),str(hosts)],text=True).strip()
        assert result=='0',result
        remote_index=json.loads((server_root/'shared/TXT/.chaosbox-sync/checksums.json').read_text())
        assert valid_manifest(remote_index)
        assert remote_index['checksum']==json.loads((first/'TXT/.chaosbox-sync/checksums.json').read_text())['checksum']
    def python():
        config=dict(host='127.0.0.1',port=port,user='tester',keyfile=str(keypath),knownhosts=str(hosts))
        with sftp_connection(config,threading.Event(),progress=lambda _:None) as sftp:
            sftp_exchange(store,sftp,'shared/TXT/.chaosbox-sync',progress=lambda _:None)
        store.apply()
        remote_index=json.loads((server_root/'shared/TXT/.chaosbox-sync/checksums.json').read_text())
        assert valid_manifest(remote_index) and remote_index['checksum']==store.checksums()['checksum']
    try:
        java();python();assert json.loads((second/'TXT/box.json').read_text())[0]['comment']=='SFTP Grüße 東京'
        checkpoint=server_root/'shared/TXT/.chaosbox-sync/media-checkpoint.json'
        receipt=checkpoint.read_bytes()
        media_reads.clear();java()
        assert not media_reads,media_reads
        assert checkpoint.read_bytes()==receipt
        record_only=json.loads((second/'TXT/box.json').read_text());record_only[0]['comment']='record-only change'
        atomic(second/'TXT/box.json',json.dumps(record_only));python()
        media_reads.clear();java();assert not media_reads,media_reads
        assert checkpoint.read_bytes()==receipt
        projected=server_root/'shared/JPG/SCHRAUBEN/Grüße photo.jpg'
        assert json.loads(projected.read_text())['comment']=='photo'
        android_photo=first/'JPG/SCHRAUBEN/Grüße photo.jpg'
        record=json.loads(android_photo.read_text());record['comment']='Android photo edit'
        atomic(android_photo,json.dumps(record))
        fail_media_rename=True
        result=subprocess.run(['java','-cp',f'{folder}:{classpath}','local.sshcopy.SftpSyncTest',str(first),str(port),str(keypath),str(hosts)],capture_output=True,text=True)
        assert result.returncode!=0
        assert not checkpoint.exists()
        assert not (server_root/'shared/TXT/.chaosbox-sync/transfer.lock').exists()
        fail_media_rename=False
        java()
        assert json.loads(projected.read_text())['comment']=='Android photo edit'
        assert checkpoint.read_bytes()!=receipt
        media_reads.clear();java();assert not media_reads,media_reads
        python()
        op=next(v[0] for v in store.heads().values() if v[0]['kind']=='media')
        record=dict(op['record'],comment='updated photo')
        photo=second/'JPG/SCHRAUBEN/Grüße photo.jpg';atomic(photo,json.dumps(record))
        python();java();assert json.loads(projected.read_text())['comment']=='updated photo'
        # A missing or corrupt receipt triggers full content verification and repairs the receipt.
        checkpoint.write_text('{invalid')
        media_reads.clear();java();assert media_reads
        json.loads(checkpoint.read_text())
        # Untracked out-of-band media must still be retained during an explicit full verification.
        checkpoint.unlink();projected.write_text('untracked server edit')
        result=subprocess.run(['java','-cp',f'{folder}:{classpath}','local.sshcopy.SftpSyncTest',str(first),str(port),str(keypath),str(hosts)],capture_output=True,text=True)
        assert result.returncode!=0 and 'Untracked remote JPG file' in result.stderr,result.stderr
        assert projected.read_text()=='untracked server edit' and not checkpoint.exists()
        # Restore the actual current payload, including the desktop edit.
        latest=next(v[0] for v in store.heads().values() if v[0]['kind']=='media')
        projected.write_bytes((store.root/'blobs'/f"{latest['blob']}.blob").read_bytes())
        java()
        store.delete(photo);python();java();assert not projected.exists()
        python()  # Receive Android's new device acknowledgement before measuring reuse.
        operation_reads.clear();python();assert not operation_reads,operation_reads
        store.delete(second/'TXT/box.json',0);python();java()
        operation_reads.clear();java();assert not operation_reads,operation_reads
        assert json.loads((first/'TXT/box.json').read_text())==[]
        assert not (server_root/'shared/TXT/.chaosbox-sync/transfer.lock').exists()
        assert (server_root/'shared/TXT/.chaosbox-sync/.htaccess').read_text()=='Require all denied\n'
        # First-sync regression: exercise both directions with more files than workers.
        bulk=folder/'bulk-desktop'
        atomic(bulk/'TXT/bulk.json',json.dumps([dict(device=str(i),comment='parallel first sync') for i in range(32)]))
        bulk_store=Store(bulk/'JPG',bulk/'TXT')
        config=dict(host='127.0.0.1',port=port,user='tester',keyfile=str(keypath),knownhosts=str(hosts))
        operation_upload_channels.clear()
        with sftp_connection(config,threading.Event(),progress=lambda _:None) as sftp:
            sftp_exchange(bulk_store,sftp,'bulk/TXT/.chaosbox-sync',progress=lambda _:None)
        assert 1<len(operation_upload_channels)<=4,operation_upload_channels
        operation_download_channels.clear()
        receiver=folder/'bulk-android'
        subprocess.run(['java','-cp',f'{folder}:{classpath}','local.sshcopy.SftpSyncTest',str(receiver),str(port),str(keypath),str(hosts),'bulk/TXT/.chaosbox-sync'],check=True,stdout=subprocess.DEVNULL)
        assert 1<len(operation_download_channels)<=4,operation_download_channels
        assert len(json.loads((receiver/'TXT/bulk.json').read_text()))==32
        # Android uploads in parallel, too. A failed publication must release the lock,
        # withhold the checksum, and remain resumable without losing immutable files.
        atomic(receiver/'TXT/new.json',json.dumps([dict(device=str(i),comment='android upload') for i in range(32)]))
        command=['java','-cp',f'{folder}:{classpath}','local.sshcopy.SftpSyncTest',str(receiver),str(port),str(keypath),str(hosts),'android-bulk/TXT/.chaosbox-sync']
        fail_op_rename=True
        failed=subprocess.run(command,capture_output=True,text=True)
        fail_op_rename=False
        assert failed.returncode!=0
        remote_bulk=server_root/'android-bulk/TXT/.chaosbox-sync'
        assert not (remote_bulk/'transfer.lock').exists() and not (remote_bulk/'checksums.json').exists()
        operation_upload_channels.clear()
        subprocess.run(command,check=True,stdout=subprocess.DEVNULL)
        assert 1<len(operation_upload_channels)<=4,operation_upload_channels
        assert valid_manifest(json.loads((remote_bulk/'checksums.json').read_text()))
        deny_extra_channels=True
        fallback=folder/'single-channel-android'
        subprocess.run(['java','-cp',f'{folder}:{classpath}','local.sshcopy.SftpSyncTest',str(fallback),str(port),str(keypath),str(hosts),'bulk/TXT/.chaosbox-sync'],check=True,stdout=subprocess.DEVNULL)
        deny_extra_channels=False
        assert len(json.loads((fallback/'TXT/bulk.json').read_text()))==32
        print('PASS: parallel first-sync Desktop upload, Android download/upload, failed-upload lock cleanup/resume and channel-limit fallback')
        # End-to-end regression: the clients' default destination must be the website's own storage.
        web_source=ROOT.parent/'chaosbox-kner-at'
        web=server_root/'l1'
        subprocess.run([sys.executable,str(web_source/'scripts/install-standalone.py'),'--installdir',str(web)],check=True,stdout=subprocess.DEVNULL)
        php='require $argv[1]; $d=exifDatasets(); echo json_encode(array_keys($d));'
        bootstrap=web/'app/bootstrap.php'
        # Simulate a browser opening the empty site before any sync has occurred.
        subprocess.run(['php','-r','require $argv[1]; (new App\\Services\\ChaosBoxCatalog(exifDataRoot()))->load();',str(bootstrap)],check=True)
        settings=core.Settings(folder/'current-desktop');settings.ensure(default_box='ChaosBox')
        profile=settings.profile(settings.default)
        atomic(profile.data/'web.json','[{"device":"visible-after-sync","comment":"SFTP to web storage"}]')
        destination=settings.ssh['datadestination']
        android_destination=subprocess.check_output(['java','-cp',f'{folder}:{classpath}','local.sshcopy.SyncDestinationTest',settings.installation_id,profile.id],text=True).strip()
        assert android_destination==destination,(android_destination,destination)
        config=dict(host='127.0.0.1',port=port,user='tester',keyfile=str(keypath),knownhosts=str(hosts))
        with sftp_connection(config,threading.Event(),progress=lambda _:None) as sftp:
            sftp_exchange(core.sync_store(profile),sftp,destination+'/.chaosbox-sync',progress=lambda _:None)
        other_android=folder/'web-android'
        subprocess.run(['java','-cp',f'{folder}:{classpath}','local.sshcopy.SftpSyncTest',str(other_android),str(port),str(keypath),str(hosts),android_destination+'/.chaosbox-sync'],check=True,stdout=subprocess.DEVNULL)
        datasets=json.loads(subprocess.check_output(['php','-r',php,str(bootstrap)],text=True))
        assert datasets==[settings.installation_id+'/chaosbox'],datasets
        php='require $argv[1]; $_GET=["installation"=>""]; $c=new App\\Services\\ChaosBoxCatalog(exifSelectedDataRoot()); echo json_encode(array_map(fn($e)=>$e["data"]->device,$c->load()));'
        visible=json.loads(subprocess.check_output(['php','-r',php,str(bootstrap)],text=True))
        assert 'visible-after-sync' in visible,visible
        assert json.loads((other_android/'TXT/web.json').read_text())[0]['device']=='visible-after-sync'
        print('PASS: Desktop and Android SFTP targets match website storage; synced journal appears on webpage even after an empty first visit')
        print('PASS: actual JSch ↔ Paramiko SFTP, named JPG creation/update/deletion, strict host verification, Unicode, deletion propagation, device ACKs, identical checksums, zero unchanged operation/media downloads, record-only fast path, failed-publication retry, media checkpoint invalidation/recovery, untracked-file protection and lock cleanup')
    finally:
        stop.set();thread.join(2);listener.close()
        for connection in connections:connection.close()
