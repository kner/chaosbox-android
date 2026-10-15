package local.sshcopy;

import com.jcraft.jsch.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;

/** Union immutable journal files over SFTP. Never infer deletion from file absence. */
final class SyncSftp {
    interface Check {void run() throws Exception;}
    static void mkdirs(ChannelSftp sftp,String path) throws Exception {
        if(path.isEmpty()||path.equals(".")||path.equals("/"))return;
        try {if(!sftp.lstat(path).isDir())throw new IOException("Unsafe remote directory: "+path);}
        catch(SftpException e) {if(e.id!=ChannelSftp.SSH_FX_NO_SUCH_FILE)throw e;int slash=path.lastIndexOf('/');if(slash>0)mkdirs(sftp,path.substring(0,slash));sftp.mkdir(path);}
    }
    static JSONObject inventory(Vector<ChannelSftp.LsEntry> entries) throws Exception {
        JSONObject result=new JSONObject();
        for(ChannelSftp.LsEntry entry:entries)if(entry.getFilename().endsWith(".op")&&entry.getAttrs().isReg()) {
            JSONObject attrs=new JSONObject();attrs.put("size",entry.getAttrs().getSize());attrs.put("mtime",Integer.toUnsignedLong(entry.getAttrs().getMTime()));
            result.put(entry.getFilename(),attrs);
        }
        return result;
    }
    static JSONObject readManifest(ChannelSftp sftp,String remote) {
        try(InputStream in=sftp.get(remote+"/checksums.json");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] bytes=new byte[8192];for(int n;(n=in.read(bytes))!=-1;) {out.write(bytes,0,n);if(out.size()>16777216)return null;}
            JSONObject value=new JSONObject(out.toString("UTF-8"));return SyncStore.validManifest(value)?value:null;
        }catch(Exception ignored){return null;}
    }
    static void publishManifest(SyncStore store,ChannelSftp sftp,String remote,Check check,JSONObject previous) throws Exception {
        @SuppressWarnings("unchecked") Vector<ChannelSftp.LsEntry> entries=sftp.ls(remote+"/ops");
        JSONObject index=SyncStore.checksumManifest(store.operations(),inventory(entries));
        Set<String> actual=new HashSet<>();for(ChannelSftp.LsEntry entry:entries)if(entry.getFilename().endsWith(".op")&&entry.getAttrs().isReg())actual.add(entry.getFilename());
        Set<String> expected=new HashSet<>();index.getJSONObject("ops").keys().forEachRemaining(expected::add);
        if(!actual.equals(expected))throw new IOException("Remote journal changed while publishing checksum index");
        byte[] bytes=(SyncStore.canonical(index)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(previous!=null&&SyncStore.canonical(previous).equals(SyncStore.canonical(index))) {
            check.run();SyncStore.atomic(new File(store.root,"checksums.json"),bytes);return;
        }
        String temporary=remote+"/.checksums-"+UUID.randomUUID()+".tmp",target=remote+"/checksums.json";
        try {
            check.run();sftp.put(new ByteArrayInputStream(bytes),temporary);check.run();
            boolean exists=true;try{sftp.lstat(target);}catch(SftpException e){if(e.id!=ChannelSftp.SSH_FX_NO_SUCH_FILE)throw e;exists=false;}
            if(exists)sftp.rm(target);sftp.rename(temporary,target);
            SyncStore.atomic(new File(store.root,"checksums.json"),bytes);
        }finally{try{sftp.rm(temporary);}catch(SftpException ignored){}}
    }
    static void exchange(SyncStore store,ChannelSftp sftp,String remote,Check check,Consumer<String> log) throws Exception {
        mkdirs(sftp,remote);String lock=remote+"/transfer.lock";
        sftp.mkdir(lock);
        Path staging=Files.createTempDirectory(store.root.toPath(),".incoming-");
        try {
            try{sftp.lstat(remote+"/.htaccess");}catch(SftpException e){if(e.id!=ChannelSftp.SSH_FX_NO_SUCH_FILE)throw e;sftp.put(new ByteArrayInputStream("Require all denied\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),remote+"/.htaccess");}
            JSONObject serverIndex=readManifest(sftp,remote),localIndex=store.checksums();
            Map<String,Set<String>> remoteFiles=new HashMap<>();
            for(String folder:new String[]{"blobs","ops"}) {
                check.run();String suffix=folder.equals("blobs")?".blob":".op",directory=remote+"/"+folder;
                mkdirs(sftp,directory);File local=new File(staging.toFile(),folder);Files.createDirectories(local.toPath());
                @SuppressWarnings("unchecked") Vector<ChannelSftp.LsEntry> entries=sftp.ls(directory);
                boolean useIndex=false;
                if(folder.equals("ops")&&serverIndex!=null&&serverIndex.optJSONObject("inventory")!=null) {
                    Set<String> present=new HashSet<>(),indexed=new HashSet<>();
                    for(ChannelSftp.LsEntry e:entries)if(e.getFilename().endsWith(".op")&&e.getAttrs().isReg())present.add(e.getFilename());
                    serverIndex.getJSONObject("ops").keys().forEachRemaining(indexed::add);
                    useIndex=present.equals(indexed)&&SyncStore.canonical(inventory(entries)).equals(SyncStore.canonical(serverIndex.getJSONObject("inventory")));
                    if(useIndex)log.accept("Prüfsummenindex geprüft; nur fehlende Änderungen herunterladen.");
                }
                Set<String> remoteNames=new HashSet<>();remoteFiles.put(folder,remoteNames);
                for(ChannelSftp.LsEntry entry:entries) {
                    check.run();String name=entry.getFilename();
                    if(!name.endsWith(suffix)||!entry.getAttrs().isReg())continue;
                    if(!name.substring(0,name.length()-suffix.length()).matches("[a-f0-9-]{1,64}"))throw new IOException("Unsafe remote journal filename");
                    remoteNames.add(name);
                    File known=new File(store.root,folder+"/"+name);
                    if(folder.equals("ops")&&useIndex&&localIndex.getJSONObject("ops").has(name)&&localIndex.getJSONObject("ops").getString(name).equals(serverIndex.getJSONObject("ops").getString(name)))continue;
                    if(folder.equals("blobs")&&known.isFile()&&!Files.isSymbolicLink(known.toPath())&&SyncStore.hash(known).equals(name.substring(0,name.length()-suffix.length())))continue;
                    try(OutputStream out=Files.newOutputStream(new File(local,name).toPath())) {
                        sftp.get(directory+"/"+name,out,new SftpProgressMonitor(){
                            public void init(int op,String src,String dst,long max){}public boolean count(long n){try{check.run();return true;}catch(Exception e){return false;}}public void end(){}
                        });
                    }
                    check.run();
                }
            }
            store.importFrom(staging.toFile());
            store.capture();
            store.apply();
            store.acknowledge();
            for(String folder:new String[]{"blobs","ops"}) {
                String suffix=folder.equals("blobs")?".blob":".op",directory=remote+"/"+folder;
                File[] files=new File(store.root,folder).listFiles((d,n)->n.endsWith(suffix));if(files==null)continue;Arrays.sort(files);
                for(File file:files) {
                    check.run();if(remoteFiles.get(folder).contains(file.getName()))continue;
                    String temporary=directory+"/."+UUID.randomUUID()+".tmp";
                    try {
                        sftp.put(file.getPath(),temporary,new SftpProgressMonitor(){
                            public void init(int op,String src,String dst,long max){}public boolean count(long n){try{check.run();return true;}catch(Exception e){return false;}}public void end(){}
                        });check.run();sftp.rename(temporary,directory+"/"+file.getName());
                    } finally {try{sftp.rm(temporary);}catch(SftpException ignored){}}
                }
            }
            store.heads(store.operations());
            publishManifest(store,sftp,remote,check,serverIndex);
            log.accept("Prüfsumme lokal und am Server gleich; Journal synchronisiert.");
            log.accept("Immutable journal exchanged over SFTP.");
        }finally{
            try(java.util.stream.Stream<Path> files=Files.walk(staging)) {for(Path path:files.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(path);}
            finally{sftp.rmdir(lock);}
        }
    }
    static SyncStore store(StoragePaths paths) throws Exception {
        SyncStore store = new SyncStore(paths.images,paths.data,new SyncStore.Metadata(){
            public JSONObject read(File file) throws Exception {
                String raw=Mp4Comments.isVideo(file.getName())?Mp4Comments.read(new FileInputStream(file)):
                    EditorActivity.ImageProcessor.readUserComment(new FileInputStream(file));
                try{return new JSONObject(raw);}catch(JSONException e){JSONObject record=new JSONObject();record.put("comment",raw);return record;}
            }
            public void write(File file,JSONObject record) throws Exception {
                if(Mp4Comments.isVideo(file.getName())) {
                    Path temporary=Files.createTempFile(file.getParentFile().toPath(),".sync-",".mp4");
                    try{Files.copy(file.toPath(),temporary,StandardCopyOption.REPLACE_EXISTING);Mp4Comments.write(temporary.toFile(),record.toString());Files.move(temporary,file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                    finally{Files.deleteIfExists(temporary);}
                }else{
                    byte[] result=XmpRecords.write(JpegComments.update(Files.readAllBytes(file.toPath()),null,new byte[0]),record.toString());
                    SyncStore.atomic(file,result);
                }
            }
        });
        store.legacyData=paths.legacyData;
        return store;
    }
    static Map<String,String> settings(android.content.Context context) throws Exception {
        File file=new File(android.os.Environment.getExternalStorageDirectory(),Config.SETUP);String text;
        if(file.isFile())text=LocalData.read(file);else try(InputStream in=context.getAssets().open(Config.SETUP_ASSET)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))!=-1;)out.write(buffer,0,n);text=out.toString("UTF-8");}
        Map<String,String> result=new HashMap<>();result.put("host",Config.HOST);result.put("port",Integer.toString(Config.PORT));result.put("user",Config.USER);
        String profile=AppSettings.load(context).profile.id;
        String remoteRoot=(profile.equalsIgnoreCase("chaosbox")||profile.equalsIgnoreCase("chaobox"))?"chaosbox-data":"chaosbox-data/profiles/"+profile.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_/-]","_");
        result.put("datadestination",remoteRoot+"/TXT");result.put("imagedestination",Config.IMAGE_DESTINATION);
        Map<String,String> overrides=new HashMap<>();
        boolean active=false,specific=false;
        for(String line:text.split("\\r?\\n")){line=line.trim();if(line.startsWith("[")){active=line.equalsIgnoreCase("[SSH]");specific=line.equalsIgnoreCase("[SSH."+profile+"]");continue;}
            int equal=line.indexOf('=');if((active||specific)&&equal>0&&!line.startsWith(";")&&!line.startsWith("#")){String value=line.substring(equal+1).trim();if(value.startsWith("\"")&&value.endsWith("\""))value=value.substring(1,value.length()-1);(specific?overrides:result).put(line.substring(0,equal).trim().toLowerCase(Locale.ROOT),value);}}
        result.putAll(overrides);
        return result;
    }
}
