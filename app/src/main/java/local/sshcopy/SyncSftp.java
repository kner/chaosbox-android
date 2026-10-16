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
    interface Transfer<T> {void run(T item,ChannelSftp channel) throws Exception;}
    /** Each worker owns its SFTP channel; the main channel and transfer lock stay on the caller. */
    static <T> void transfer(List<T> items,ChannelSftp main,Check check,Transfer<T> action) throws Exception {
        if(items.size()<=4) {for(T item:items){check.run();action.run(item,main);}return;}
        List<ChannelSftp> channels=new ArrayList<>();
        java.util.concurrent.ExecutorService pool=null;
        try {
            for(int i=0;i<Math.min(4,items.size());i++) {
                check.run();ChannelSftp channel=null;
                try {channel=(ChannelSftp)main.getSession().openChannel("sftp");channel.connect(15000);channels.add(channel);}
                catch(JSchException unavailable){if(channel!=null)channel.disconnect();break;}
            }
            if(channels.isEmpty()){for(T item:items){check.run();action.run(item,main);}return;}
            pool=java.util.concurrent.Executors.newFixedThreadPool(channels.size());
            java.util.concurrent.atomic.AtomicInteger next=new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.CompletionService<Void> completed=new java.util.concurrent.ExecutorCompletionService<>(pool);
            for(ChannelSftp channel:channels)completed.submit(()->{
                for(int index;(index=next.getAndIncrement())<items.size();) {
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException("Transfer stopped");
                    check.run();action.run(items.get(index),channel);
                }
                return null;
            });
            for(int i=0;i<channels.size();i++) {
                try{completed.take().get();}
                catch(java.util.concurrent.ExecutionException error) {
                    Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IOException(cause);
                }
            }
        }finally {
            if(pool!=null)pool.shutdownNow();
            for(ChannelSftp channel:channels)channel.disconnect();
            // Join workers before releasing transfer.lock or publishing a checksum/checkpoint.
            if(pool!=null) {
                boolean interrupted=false;
                while(true)try{if(pool.awaitTermination(1,java.util.concurrent.TimeUnit.SECONDS))break;}
                catch(InterruptedException error){interrupted=true;}
                if(interrupted)Thread.currentThread().interrupt();
            }
        }
    }
    static void mkdirs(ChannelSftp sftp,String path) throws Exception {
        if(path.isEmpty()||path.equals(".")||path.equals("/"))return;
        int parent=path.lastIndexOf('/');if(parent>0)mkdirs(sftp,path.substring(0,parent));
        try {if(!sftp.lstat(path).isDir())throw new IOException("Unsafe remote directory: "+path);}
        catch(SftpException e) {if(e.id!=ChannelSftp.SSH_FX_NO_SUCH_FILE)throw e;sftp.mkdir(path);}
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
    static String remoteHash(ChannelSftp sftp,String path,Check check) throws Exception {
        try {if(!sftp.lstat(path).isReg())throw new IOException("Unsafe remote media: "+path);}
        catch(SftpException e){if(e.id==ChannelSftp.SSH_FX_NO_SUCH_FILE)return null;throw e;}
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        try(InputStream in=sftp.get(path)) {
            byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;){check.run();digest.update(buffer,0,n);}
        }
        StringBuilder result=new StringBuilder();for(byte b:digest.digest())result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return result.toString();
    }
    static String parentPath(String path) {int slash=path.lastIndexOf('/');return slash<0?"":slash==0?"/":path.substring(0,slash);}
    // A receipt is published only after the named JPG projection completes under transfer.lock.
    // Journal-only clients remain compatible: any new media operation changes this checksum.
    static String mediaChecksum(Map<String,JSONObject> operations) throws Exception {
        JSONObject media=new JSONObject();
        for(Map.Entry<String,JSONObject> entry:operations.entrySet())
            if(entry.getValue().getString("kind").equals("media"))media.put(entry.getKey(),entry.getValue());
        return SyncStore.hash(SyncStore.canonical(media).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    static boolean mediaCheckpointMatches(ChannelSftp sftp,String remote,String images,String checksum,Check check) throws Exception {
        try(InputStream in=sftp.get(remote+"/media-checkpoint.json");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] bytes=new byte[1024];for(int n;(n=in.read(bytes))!=-1;) {
                check.run();out.write(bytes,0,n);if(out.size()>4096)return false;
            }
            JSONObject receipt=new JSONObject(out.toString("UTF-8"));
            return receipt.optInt("schema")==1&&images.equals(receipt.optString("images"))&&checksum.equals(receipt.optString("media_checksum"));
        }catch(SftpException|JSONException ignored){return false;}
    }
    static void publishMediaCheckpoint(ChannelSftp sftp,String remote,String images,String checksum,Check check) throws Exception {
        JSONObject receipt=new JSONObject();receipt.put("schema",1);receipt.put("images",images);receipt.put("media_checksum",checksum);
        String temporary=remote+"/.media-checkpoint-"+UUID.randomUUID()+".tmp";
        try {
            check.run();sftp.put(new ByteArrayInputStream((SyncStore.canonical(receipt)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)),temporary);
            check.run();sftp.rename(temporary,remote+"/media-checkpoint.json");
        }finally{try{sftp.rm(temporary);}catch(SftpException ignored){}}
    }
    static void publishImages(SyncStore store,ChannelSftp sftp,String remote,Check check,Consumer<String> log) throws Exception {
        Map<String,JSONObject> operations=store.operations();
        Map<String,Set<String>> known=new TreeMap<>();Map<String,String> desired=new TreeMap<>(),current=new TreeMap<>();Set<String> protectedPaths=new HashSet<>();
        for(JSONObject op:operations.values())if(op.getString("kind").equals("media")&&op.getString("action").equals("put"))
            known.computeIfAbsent(op.getString("path"),key->new HashSet<>()).add(op.getString("blob"));
        for(List<JSONObject> versions:store.heads(operations).values()) {
            JSONObject op=versions.get(0);if(!op.getString("kind").equals("media"))continue;
            if(versions.size()>1) {
                for(JSONObject old:operations.values())if(old.getString("id").equals(op.getString("id")))protectedPaths.add(old.getString("path"));
            }else if(op.getString("action").equals("put")) {
                if(desired.put(op.getString("path"),op.getString("blob"))!=null)throw new IOException("Media filename conflict: "+op.getString("path"));
            }
        }
        for(String path:protectedPaths)desired.remove(path);
        String base=parentPath(parentPath(remote)),images=(base.isEmpty()?"":base.endsWith("/")?base:base+"/")+"JPG";
        String checksum=mediaChecksum(operations);
        check.run();
        if(mediaCheckpointMatches(sftp,remote,images,checksum,check)) {
            log.accept("Media journal unchanged; skipping remote JPG checks and transfers.");
            return;
        }
        // Remove an older receipt before any media mutation. A failed run must be verified again.
        try{sftp.rm(remote+"/media-checkpoint.json");}catch(SftpException e){if(e.id!=ChannelSftp.SSH_FX_NO_SUCH_FILE)throw e;}
        log.accept("Media journal changed or checkpoint missing; verifying remote JPG/ …");mkdirs(sftp,images);
        Set<String> directories=new HashSet<>();directories.add(images);
        int verified=0;
        for(String path:known.keySet()) {
            if(protectedPaths.contains(path))continue;check.run();String target=images+"/"+path;String parent=parentPath(target);if(directories.add(parent))mkdirs(sftp,parent);
            log.accept("Checking remote media "+(++verified)+"/"+known.size()+": "+path);
            String hash=remoteHash(sftp,target,check);current.put(path,hash);
            if(desired.containsKey(path)&&hash!=null&&!known.get(path).contains(hash))throw new IOException("Untracked remote JPG file; retained: "+path);
        }
        for(Map.Entry<String,String> entry:desired.entrySet()) {
            String path=entry.getKey(),blob=entry.getValue();check.run();if(blob.equals(current.get(path)))continue;
            File source=new File(store.root,"blobs/"+blob+".blob");if(!SyncStore.hash(source).equals(blob))throw new IOException("Corrupt media payload: "+path);
            String target=images+"/"+path,temporary=target+"."+UUID.randomUUID()+".tmp";
            try {
                sftp.put(source.getPath(),temporary,new SftpProgressMonitor(){
                    public void init(int op,String src,String dst,long max){}public boolean count(long n){try{check.run();return true;}catch(Exception e){return false;}}public void end(){}
                });check.run();
                // JSch uses posix-rename when the server advertises that extension.
                if(current.get(path)!=null&&!"1".equals(sftp.getExtension("posix-rename@openssh.com")))sftp.rm(target);
                sftp.rename(temporary,target);
            }finally{try{sftp.rm(temporary);}catch(SftpException ignored){}}
        }
        for(Map.Entry<String,String> entry:current.entrySet())if(!desired.containsKey(entry.getKey())&&known.get(entry.getKey()).contains(entry.getValue())) {
            check.run();sftp.rm(images+"/"+entry.getKey());
        }
        publishMediaCheckpoint(sftp,remote,images,checksum,check);
    }
    static void exchange(SyncStore store,ChannelSftp sftp,String remote,Check check,Consumer<String> log) throws Exception {
        mkdirs(sftp,remote);String lock=remote+"/transfer.lock";
        sftp.mkdir(lock);
        Path staging=Files.createTempDirectory(store.root.toPath(),".incoming-");
        try {
            try{sftp.lstat(remote+"/.htaccess");}catch(SftpException e){if(e.id!=ChannelSftp.SSH_FX_NO_SUCH_FILE)throw e;sftp.put(new ByteArrayInputStream("Require all denied\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),remote+"/.htaccess");}
            log.accept("Reading journal checksum indexes …");
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
                log.accept("Checking remote "+folder+" ("+entries.size()+" entries) …");
                List<ChannelSftp.LsEntry> pending=new ArrayList<>();
                Set<String> remoteNames=new HashSet<>();remoteFiles.put(folder,remoteNames);
                for(ChannelSftp.LsEntry entry:entries) {
                    check.run();String name=entry.getFilename();
                    if(!name.endsWith(suffix)||!entry.getAttrs().isReg())continue;
                    if(!name.substring(0,name.length()-suffix.length()).matches("[a-f0-9-]{1,64}"))throw new IOException("Unsafe remote journal filename");
                    remoteNames.add(name);
                    File known=new File(store.root,folder+"/"+name);
                    if(folder.equals("ops")&&useIndex&&localIndex.getJSONObject("ops").has(name)&&localIndex.getJSONObject("ops").getString(name).equals(serverIndex.getJSONObject("ops").getString(name)))continue;
                    if(folder.equals("blobs")&&known.isFile()&&!Files.isSymbolicLink(known.toPath())&&SyncStore.hash(known).equals(name.substring(0,name.length()-suffix.length())))continue;
                    pending.add(entry);
                }
                java.util.concurrent.atomic.AtomicInteger downloaded=new java.util.concurrent.atomic.AtomicInteger();
                Transfer<ChannelSftp.LsEntry> download=(entry,reader)->{
                    String name=entry.getFilename();
                    log.accept("Downloading "+folder+" "+downloaded.incrementAndGet()+"/"+pending.size()+": "+name);
                    try(OutputStream out=Files.newOutputStream(new File(local,name).toPath())) {
                        reader.get(directory+"/"+name,out,new SftpProgressMonitor(){
                            public void init(int op,String src,String dst,long max){}public boolean count(long n){try{check.run();return !Thread.currentThread().isInterrupted();}catch(Exception e){return false;}}public void end(){}
                        });
                    }
                    check.run();
                };
                if(folder.equals("ops")) {
                    if(pending.size()>4)log.accept("Downloading changes over up to 4 parallel SFTP channels …");
                    transfer(pending,sftp,check,download);
                }else for(ChannelSftp.LsEntry entry:pending)download.run(entry,sftp);
            }
            check.run();log.accept("Validating downloaded journal and media …");
            store.importFrom(staging.toFile());
            check.run();log.accept("Scanning local files for changes …");
            store.capture();
            check.run();log.accept("Applying synchronized changes …");
            store.apply();
            check.run();store.acknowledge();
            for(String folder:new String[]{"blobs","ops"}) {
                String suffix=folder.equals("blobs")?".blob":".op",directory=remote+"/"+folder;
                File[] files=new File(store.root,folder).listFiles((d,n)->n.endsWith(suffix));if(files==null)continue;Arrays.sort(files);
                List<File> pending=new ArrayList<>();
                for(File file:files)if(!remoteFiles.get(folder).contains(file.getName()))pending.add(file);
                java.util.concurrent.atomic.AtomicInteger uploaded=new java.util.concurrent.atomic.AtomicInteger();
                Transfer<File> upload=(file,writer)->{
                    check.run();log.accept("Uploading "+folder+" "+uploaded.incrementAndGet()+"/"+pending.size()+": "+file.getName());
                    String temporary=directory+"/."+UUID.randomUUID()+".tmp";boolean published=false;
                    try {
                        writer.put(file.getPath(),temporary,new SftpProgressMonitor(){
                            public void init(int op,String src,String dst,long max){}public boolean count(long n){try{check.run();return !Thread.currentThread().isInterrupted();}catch(Exception e){return false;}}public void end(){}
                        });check.run();
                        if(Thread.currentThread().isInterrupted())throw new InterruptedException("Transfer stopped");
                        writer.rename(temporary,directory+"/"+file.getName());published=true;
                    } finally {if(!published)try{writer.rm(temporary);}catch(SftpException ignored){}}
                };
                if(folder.equals("ops")) {
                    if(pending.size()>4)log.accept("Uploading changes over up to 4 parallel SFTP channels …");
                    transfer(pending,sftp,check,upload);
                }else for(File file:pending)upload.run(file,sftp);
            }
            store.heads(store.operations());
            publishImages(store,sftp,remote,check,log);
            log.accept("Publishing journal checksums …");
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
        File file=Config.setup(context);
        if(!file.isFile())throw new IOException("Fresh installation: configure Installation in "+file+" before synchronization.");
        String text=LocalData.read(file);
        Map<String,String> result=new HashMap<>();result.put("host",Config.HOST);result.put("port",Integer.toString(Config.PORT));result.put("user",Config.USER);
        String profile=AppSettings.load(context).profile.id;
        Map<String,String> overrides=new HashMap<>();
        boolean active=false,specific=false;
        for(String line:text.split("\\r?\\n")){line=line.trim();if(line.startsWith("[")){active=line.equalsIgnoreCase("[SSH]");specific=line.equalsIgnoreCase("[SSH."+profile+"]");continue;}
            int equal=line.indexOf('=');if((active||specific)&&equal>0&&!line.startsWith(";")&&!line.startsWith("#")){String value=line.substring(equal+1).trim();if(value.startsWith("\"")&&value.endsWith("\""))value=value.substring(1,value.length()-1);(specific?overrides:result).put(line.substring(0,equal).trim().toLowerCase(Locale.ROOT),value);}}
        result.putAll(overrides);
        SyncDestination.select(result,profile);
        return result;
    }
}
