#!/usr/bin/env python3
"""Cross-language journal exchange: Android Java ↔ desktop Python ↔ read-only PHP."""
import argparse,sys,tempfile,subprocess,json,shutil
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--json-jar',required=True,type=Path);p.add_argument('--desktop',type=Path);a=p.parse_args()
ROOT=Path(__file__).resolve().parents[1];DESKTOP=a.desktop or ROOT.parent/'chaosbox-desktop';WEB=ROOT.parent/'chaosbox-kner-at'
sys.path.insert(0,str(DESKTOP))
import core,chaos_sync
from PIL import Image

def run(*args):return subprocess.check_output(list(map(str,args)),text=True).strip()
with tempfile.TemporaryDirectory(prefix='chaosbox-wire-') as temp:
    root=Path(temp);classes=root/'classes';classes.mkdir()
    files=['SyncStore','LocalData','BoxRecords','XmpRecords','JpegComments','Mp4Comments']
    run('javac','-cp',a.json_jar,'-d',classes,*[ROOT/f'app/src/main/java/local/sshcopy/{name}.java' for name in files],ROOT/'tests/SyncStoreTest.java',ROOT/'tests/MediaFilenameConflictTest.java')
    def java(command,folder,*args):return run('java','-cp',f'{classes}:{a.json_jar}','local.sshcopy.SyncStoreTest',command,folder,*args)
    for key in ['comment','kommentar','Kommentar']:
        for value in [None,'','null','Grüße 東京']:
            record={key:value}
            actual=json.loads(java('normalize',root,json.dumps(record)))
            assert actual==chaos_sync.normalized(record),(record,actual)
            assert actual['comment']==('' if value is None else value)
    def store(folder):
        profile=core.Profile('','',folder/'JPG',folder/'TXT',folder/'TXT',folder/'.indices',[],[])
        return core.sync_store(profile)
    def exchange(first,second):
        for source,target in [(first,second),(second,first)]:
            for folder,suffix in [('blobs','.blob'),('ops','.op')]:
                for file in (source.root/folder).glob('*'+suffix):target.immutable(target.root/folder/file.name,file.read_bytes())
    desktop,android=root/'desktop',root/'android'
    fixture=root/'clip.mp4'
    run('ffmpeg','-v','error','-f','lavfi','-i','color=black:s=16x16:r=1','-t','1','-c:v','libx264','-pix_fmt','yuv420p',fixture)
    for folder in [desktop,android]:
        (folder/'TXT').mkdir(parents=True);(folder/'JPG').mkdir()
        (folder/'TXT/box.json').write_text('[{"box":"A11","device":"one","comment":"Grüße 東京"},{"box":"A11","device":"two","comment":null}]')
        Image.new('RGB',(20,10)).save(folder/'JPG/photo.jpg')
        shutil.copy(fixture,folder/'JPG/clip.mp4')
        (folder/'TXT/grouped.json').write_text('{"z":{"category":"Werkzeug"},"a":{"alias":"Reserve","comment":"grouped"}}')
    droid=store(android);desk=store(desktop)
    desk.capture();java('capture',android)
    # Both implementations must produce byte-identical deterministic baseline operations.
    assert desk.operations()==droid.operations(),(desk.operations(),droid.operations())
    assert desk.checksums()==json.loads(java('checksums',android)), 'Checksum format differs across Java and Python'
    exchange(desk,droid);desk.apply();java('apply',android)
    java('edit',android,'box.json',0,'Android edit 🎬')
    records=json.loads((desktop/'TXT/box.json').read_text());records[1]['comment']='Desktop edit'
    chaos_sync.atomic(desktop/'TXT/box.json',chaos_sync.canonical(records));desk.capture()
    exchange(desk,droid);desk.apply();assert java('apply',android)=='0'
    assert json.loads((desktop/'TXT/box.json').read_text())==json.loads((android/'TXT/box.json').read_text())
    java('delete',android,'box.json',0);exchange(desk,droid);desk.apply();java('apply',android)
    assert len(json.loads((desktop/'TXT/box.json').read_text()))==1
    java('edit-media',android,'photo.jpg','Android picture edit')
    exchange(desk,droid);desk.apply();assert core.read_metadata(desktop/'JPG/photo.jpg')['comment']=='Android picture edit'
    java('edit-media',android,'clip.mp4','Video edit 🎬')
    exchange(desk,droid);desk.apply()
    assert core.read_metadata(desktop/'JPG/clip.mp4')['comment']=='Video edit 🎬'
    def stream_hash(path):return run('ffmpeg','-v','error','-i',path,'-map','0:v','-c','copy','-f','streamhash','-')
    assert stream_hash(fixture)==stream_hash(desktop/'JPG/clip.mp4')
    # Delete on Desktop versus offline edit on Android: retain both heads.
    desk.delete(desktop/'JPG/photo.jpg')
    java('edit-media',android,'photo.jpg','offline edit')
    exchange(desk,droid);assert len(desk.apply())==1;assert java('apply',android)=='1'
    ident,heads=next((i,h) for i,h in desk.heads().items() if len(h)>1)
    deletion=next(op for op in heads if op['action']=='delete')
    java('resolve',android,ident,deletion['revision']);exchange(desk,droid);assert not desk.apply()
    assert not (desktop/'JPG/photo.jpg').exists()
    # The server needs no writable source files; it projects only the journal.
    script='require $argv[1];$c=new App\\Services\\ChaosBoxCatalog($argv[2]);echo json_encode($c->rebuild());'
    before={f.relative_to(droid.data):f.read_bytes() for f in droid.data.rglob('*') if f.is_file()}
    entries=json.loads(run('php','-r',script,WEB/'app/Services/ChaosBoxCatalog.php',android))
    assert len(entries)==3,entries
    assert before=={f.relative_to(droid.data):f.read_bytes() for f in droid.data.rglob('*') if f.is_file()}
    old=next(op for op in desk.operations().values() if op['id']==ident and op['action']=='put')
    java('restore',android,ident,old['revision']);exchange(desk,droid);assert not desk.apply()
    assert (desktop/'JPG/photo.jpg').is_file()
    target=desktop/'JPG/moved/photo.jpg';target.parent.mkdir();(desktop/'JPG/photo.jpg').rename(target)
    desk.capture();exchange(desk,droid);desk.apply();java('apply',android)
    assert (android/'JPG/moved/photo.jpg').is_file() and not (android/'JPG/photo.jpg').exists()
    before=len(desk.operations());desk.capture();java('capture',android);assert len(desk.operations())==before and len(droid.operations())==before
    entries=json.loads(run('php','-r',script,WEB/'app/Services/ChaosBoxCatalog.php',android));assert len(entries)==4
    image=next(e for e in entries if e.get('payload'))
    imagepath=run('php','-r','require $argv[1];$c=new App\\Services\\ChaosBoxCatalog($argv[2]);echo $c->imagePath($argv[3]);',WEB/'app/Services/ChaosBoxCatalog.php',android,image['image'])
    assert Path(imagepath).is_file()
    collision_root=root/'filename-collisions'
    run('java','-cp',f'{classes}:{a.json_jar}','local.sshcopy.MediaFilenameConflictTest',collision_root)
    for case in ('case-false','case-true'):
        source=chaos_sync.Store(collision_root/case/'JPG',collision_root/case/'TXT')
        target=chaos_sync.Store(root/('received-'+case)/'JPG',root/('received-'+case)/'TXT')
        exchange(source,target);target.apply();target.apply()
        payloads=sorted(file.read_text() for file in target.images.rglob('*.jpg'))
        assert payloads==['one','two'],payloads
    print('PASS: identical migration; Android ↔ Desktop merge, Unicode, record deletion, JPEG/MP4 edits, unchanged video streams, media moves, filename collision repair propagated to Desktop, offline conflict, resolution, restoration, idempotence and read-only Web projection')
