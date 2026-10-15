package local.sshcopy;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Immutable, transport-independent ChaosBox synchronization journal v1. */
final class SyncStore {
    interface Metadata { JSONObject read(File file) throws Exception; void write(File file, JSONObject record) throws Exception; }
    final File images, data, root;
    File legacyData;
    final String device;
    final Metadata metadata;
    private Map<String,JSONObject> captureOperations;
    SyncStore(File images, File data, Metadata metadata) throws Exception {
        this.images=images; this.data=data; this.legacyData=data; this.metadata=metadata;
        root=new File(data,".chaosbox-sync"); Files.createDirectories(root.toPath());
        File identity=new File(root,"device");
        if(!identity.exists()) atomic(identity,UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        device=LocalData.read(identity).trim();
    }
    AutoCloseable lock() throws IOException {
        Path lock=new File(root,"local.lock").toPath();
        try { Files.createDirectory(lock); } catch(FileAlreadyExistsException e) { throw new IOException("Synchronization already running; inspect stale lock: "+lock); }
        return () -> Files.delete(lock);
    }
    static String legacyId(String kind,String path,int position) {
        return UUID.nameUUIDFromBytes((kind+":"+path+":"+position).getBytes(StandardCharsets.UTF_8)).toString();
    }
    static String canonical(Object value) throws JSONException {
        if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value; List<String> keys=new ArrayList<>(); object.keys().forEachRemaining(keys::add); keys.sort(BoxRecords::compareKeys);
            List<String> values=new ArrayList<>(); for(String key:keys) values.add(quote(key)+":"+canonical(object.get(key)));
            return "{"+String.join(",",values)+"}";
        }
        if(value instanceof JSONArray) {
            List<String> values=new ArrayList<>(); JSONArray array=(JSONArray)value;
            for(int i=0;i<array.length();i++) values.add(canonical(array.get(i)));
            return "["+String.join(",",values)+"]";
        }
        if(value==null || value==JSONObject.NULL) return "null";
        return value instanceof String ? quote((String)value) : value.toString();
    }
    static String quote(String text) {
        StringBuilder out=new StringBuilder("\"");
        for(int i=0;i<text.length();i++) { char c=text.charAt(i);
            switch(c) { case '"':out.append("\\\"");break; case '\\':out.append("\\\\");break;
                case '\n':out.append("\\n");break; case '\r':out.append("\\r");break; case '\t':out.append("\\t");break;
                case '\b':out.append("\\b");break;case '\f':out.append("\\f");break;
                default: if(c<32) out.append(String.format(Locale.ROOT,"\\u%04x",(int)c)); else out.append(c);
            }
        }
        return out.append('"').toString();
    }
    static JSONObject normalized(JSONObject record) throws JSONException {
        JSONObject result=new JSONObject(record.toString());
        result.put("anzahl",Math.max(0,result.optInt("count",result.optInt("anzahl",result.optInt("Anzahl",0)))));
        result.put("package",result.opt("pack")==null?result.optString("package",""):result.get("pack"));
        result.put("comment",commentText(result));
        Object category="";java.util.Iterator<String> keys=result.keys();java.util.List<String> remove=new ArrayList<>();
        while(keys.hasNext()){String key=keys.next();if(key.equalsIgnoreCase("category")||key.equalsIgnoreCase("kategorie")){if(category.equals(""))category=result.opt(key);if(!key.equals("category"))remove.add(key);}}
        if(category instanceof JSONArray){JSONArray array=(JSONArray)category;List<String> values=new ArrayList<>();for(int i=0;i<array.length();i++)values.add(array.optString(i));category=String.join(", ",values);}
        result.put("category",(category==null||category==JSONObject.NULL?"":category.toString()).toUpperCase(Locale.ROOT));
        for(String key:new String[]{"box","device","alias","created"})if(!result.has(key))result.put(key,"");
        for(String key:new String[]{"count","pack","Anzahl","kommentar","Kommentar","modified"})result.remove(key);
        for(String key:remove)result.remove(key);
        return result;
    }
    static String commentText(JSONObject record) {
        for(String key:new String[]{"comment","kommentar","Kommentar"}) {
            if(record.has(key)) return record.isNull(key)?"":record.optString(key,"");
        }
        return "";
    }
    static JSONObject clean(JSONObject record) throws JSONException {
        JSONObject result=normalized(record);
        for(String key:new String[]{"id","revision","base_revision","modified","path"})result.remove(key);
        return result;
    }
    static String hash(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static String hash(File file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(file.toPath())) { byte[] buffer=new byte[65536]; for(int n;(n=in.read(buffer))!=-1;)digest.update(buffer,0,n); }
        return hex(digest.digest());
    }
    static String hex(byte[] bytes) { StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString(); }
    static void atomic(File file,byte[] bytes) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        if(Files.isSymbolicLink(file.toPath()))throw new IOException("Symlink refused: "+file);
        Path temp=Files.createTempFile(file.getParentFile().toPath(),".sync-",".tmp");
        try { try(FileOutputStream out=new FileOutputStream(temp.toFile())) {out.write(bytes);out.getFD().sync();}
            Files.move(temp,file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(temp);}
    }
    static void immutable(File file,byte[] bytes) throws Exception {
        if(file.exists()) {if(!Arrays.equals(Files.readAllBytes(file.toPath()),bytes))throw new IOException("Immutable journal collision: "+file.getName());}
        else atomic(file,bytes);
    }
    static void immutable(File target, File source) throws Exception {
        if(target.exists()) {if(!hash(target).equals(hash(source)))throw new IOException("Immutable payload collision"); return;}
        Files.createDirectories(target.getParentFile().toPath());
        Path temp=Files.createTempFile(target.getParentFile().toPath(),".blob-",".tmp");
        try {Files.copy(source.toPath(),temp,StandardCopyOption.REPLACE_EXISTING);Files.move(temp,target.toPath(),StandardCopyOption.ATOMIC_MOVE);}
        finally {Files.deleteIfExists(temp);}
    }
    static void validate(JSONObject op) throws Exception {
        UUID.fromString(op.getString("id"));
        if(op.getInt("schema")!=1 || !Arrays.asList("record","media","device").contains(op.getString("kind")) || !Arrays.asList("put","delete","ack","retire").contains(op.getString("action")))throw new IOException("Unsupported operation");
        List<String> revisions=new ArrayList<>();revisions.add(op.getString("revision"));
        JSONArray parents=op.getJSONArray("parents");for(int i=0;i<parents.length();i++)revisions.add(parents.getString(i));
        for(String revision:revisions)if(!revision.matches("[a-f0-9-]{1,64}"))throw new IOException("Invalid revision");
        String path=op.getString("path");
        if(path.startsWith("/") || path.contains("\\") || path.matches(".*[\\p{Cntrl}].*"))throw new IOException("Unsafe sync path");
        for(String part:path.split("/",-1))if(part.isEmpty()||part.startsWith("."))throw new IOException("Unsafe sync path");
        if(op.getString("kind").equals("device")){
            if(!Arrays.asList("ack","retire").contains(op.getString("action"))||!path.equals("devices/"+op.getString("id")+".json")||!(op.get("seen") instanceof JSONArray))throw new IOException("Invalid device acknowledgment");return;
        }
        if(!Arrays.asList("put","delete").contains(op.getString("action")))throw new IOException("Invalid object action");
        if(!(op.getString("kind").equals("media")?path.toLowerCase(Locale.ROOT).matches(".*\\.(jpg|jpeg|mp4)$"):path.toLowerCase(Locale.ROOT).endsWith(".json")))throw new IOException("Unsupported sync file");
        if(op.getString("action").equals("put")) {
            JSONObject record=op.getJSONObject("record");
            if(!op.getString("id").equals(record.getString("id"))||!op.getString("revision").equals(record.getString("revision")))throw new IOException("Identity mismatch");
            if(op.getString("kind").equals("media")&&!op.getString("blob").matches("[a-f0-9]{64}"))throw new IOException("Invalid blob");
        }
    }
    static JSONObject checksumManifest(Map<String,JSONObject> operations,JSONObject inventory) throws Exception {
        JSONObject entries=new JSONObject();
        for(JSONObject op:operations.values())entries.put(op.getString("revision")+".op",hash(canonical(op).getBytes(StandardCharsets.UTF_8)));
        JSONObject result=new JSONObject();result.put("schema",1);result.put("algorithm","sha256");
        result.put("checksum",hash(canonical(entries).getBytes(StandardCharsets.UTF_8)));result.put("ops",entries);
        if(inventory!=null)result.put("inventory",inventory);
        return result;
    }
    static boolean validManifest(JSONObject value) throws Exception {
        if(value==null||value.optInt("schema")!=1||!value.optString("algorithm").equals("sha256"))return false;
        JSONObject entries=value.optJSONObject("ops");if(entries==null)return false;
        Iterator<String> keys=entries.keys();while(keys.hasNext()) {
            String name=keys.next();if(!name.matches("[a-f0-9-]{1,64}\\.op")||!entries.optString(name).matches("[a-f0-9]{64}"))return false;
        }
        return value.optString("checksum").equals(hash(canonical(entries).getBytes(StandardCharsets.UTF_8)));
    }
    JSONObject checksums() throws Exception {
        JSONObject value=checksumManifest(operations(),null);File target=new File(root,"checksums.json");
        String content=canonical(value)+"\n";
        if(!target.isFile()||!LocalData.read(target).equals(content))atomic(target,content.getBytes(StandardCharsets.UTF_8));
        return value;
    }
    Map<String,JSONObject> operations() throws Exception {
        if(captureOperations!=null)return captureOperations;
        Map<String,JSONObject> result=new TreeMap<>();File[] files=new File(root,"ops").listFiles((dir,name)->name.endsWith(".op"));
        if(files!=null)for(File file:files) {
            if(Files.isSymbolicLink(file.toPath()))throw new IOException("Journal symlink");
            JSONObject op=new JSONObject(LocalData.read(file));validate(op);
            if(!file.getName().equals(op.getString("revision")+".op"))throw new IOException("Operation filename mismatch");
            result.put(op.getString("revision"),op);
        }
        return result;
    }
    void importFrom(File directory) throws Exception {
        Map<String,JSONObject> combined=operations();List<JSONObject> incoming=new ArrayList<>();
        File[] files=new File(directory,"ops").listFiles((dir,name)->name.endsWith(".op"));
        if(files!=null)for(File file:files){
            if(Files.isSymbolicLink(file.toPath()))throw new IOException("Journal symlink");
            JSONObject op=new JSONObject(LocalData.read(file));validate(op);
            String revision=op.getString("revision");if(!file.getName().equals(revision+".op"))throw new IOException("Operation filename mismatch");
            if(combined.containsKey(revision)&&!canonical(combined.get(revision)).equals(canonical(op)))throw new IOException("Immutable operation collision");
            combined.put(revision,op);incoming.add(op);
        }
        heads(combined);
        for(JSONObject op:combined.values())if(op.has("blob")){
            File source=new File(directory,"blobs/"+op.getString("blob")+".blob");if(!source.exists())source=new File(root,"blobs/"+op.getString("blob")+".blob");
            if(Files.isSymbolicLink(source.toPath())||!source.isFile()||!hash(source).equals(op.getString("blob")))throw new IOException("Missing or corrupt media payload");
        }
        for(JSONObject op:incoming){
            if(op.has("blob")){File source=new File(directory,"blobs/"+op.getString("blob")+".blob");if(!source.exists())source=new File(root,"blobs/"+op.getString("blob")+".blob");immutable(new File(root,"blobs/"+op.getString("blob")+".blob"),source);}
            immutable(new File(root,"ops/"+op.getString("revision")+".op"),canonical(op).getBytes(StandardCharsets.UTF_8));
        }
    }
    Map<String,List<JSONObject>> heads(Map<String,JSONObject> operations) throws Exception {
        Set<String> referenced=new HashSet<>();
        for(JSONObject op:operations.values()) {JSONArray parents=op.getJSONArray("parents");for(int i=0;i<parents.length();i++) {
            String revision=parents.getString(i);JSONObject parent=operations.get(revision);
            if(parent==null || !parent.getString("id").equals(op.getString("id")) || !parent.getString("kind").equals(op.getString("kind")))throw new IOException("Incomplete or invalid journal parent: "+revision);
            referenced.add(revision);
        }}
        Map<String,Integer> remaining=new HashMap<>();Map<String,List<String>> children=new HashMap<>();Deque<String> ready=new ArrayDeque<>();
        for(JSONObject op:operations.values()){String revision=op.getString("revision");JSONArray parents=op.getJSONArray("parents");remaining.put(revision,parents.length());if(parents.length()==0)ready.add(revision);for(int i=0;i<parents.length();i++)children.computeIfAbsent(parents.getString(i),k->new ArrayList<>()).add(revision);}
        int processed=0;while(!ready.isEmpty()){String revision=ready.remove();processed++;for(String child:children.getOrDefault(revision,Collections.emptyList())){int count=remaining.get(child)-1;remaining.put(child,count);if(count==0)ready.add(child);}}
        if(processed!=operations.size())throw new IOException("Cyclic journal history");
        Map<String,List<JSONObject>> result=new TreeMap<>();
        for(JSONObject op:operations.values())if(!referenced.contains(op.getString("revision")))result.computeIfAbsent(op.getString("id"),k->new ArrayList<>()).add(op);
        return result;
    }
    JSONObject issue(String kind,String relative,JSONObject record,File media,boolean deleted,JSONArray parents,int position) throws Exception {
        String ident=record.optString("id","");if(ident.isEmpty())ident=legacyId(kind,relative,position);
        String previous=record.optString("revision","");Map<String,JSONObject> operations=operations();
        boolean baseline=previous.isEmpty()&&!deleted;
        for(JSONObject op:operations.values())if(ident.equals(op.getString("id")))baseline=false;
        String seed="baseline:"+ident+":"+canonical(clean(record))+":"+(media==null?"":hash(media));
        String revision=baseline?hash(seed.getBytes(StandardCharsets.UTF_8)):UUID.randomUUID().toString();
        if(parents==null) {
            parents=new JSONArray();String parent=operations.containsKey(previous)?previous:previous.isEmpty()?"":record.optString("base_revision","");
            JSONObject ancestor=operations.get(parent);
            if(ancestor!=null&&ancestor.getString("id").equals(ident)&&ancestor.getString("kind").equals(kind))parents.put(parent);
        }
        JSONObject updated=new JSONObject(record.toString());JSONObject normalized=normalized(record);java.util.Iterator<String> canonicalKeys=normalized.keys();while(canonicalKeys.hasNext()){String key=canonicalKeys.next();updated.put(key,normalized.get(key));}updated.put("id",ident);updated.put("revision",revision);updated.remove("modified");updated.remove("base_revision");if(parents.length()==1)updated.put("base_revision",parents.getString(0));else if(!baseline)updated.put("base_revision","");
        if(media!=null&&!baseline&&metadata!=null&&!media.toPath().startsWith(new File(root,"blobs").toPath()))metadata.write(media,updated);
        JSONObject op=new JSONObject();op.put("schema",1);op.put("id",ident);op.put("revision",revision);op.put("parents",parents);
        op.put("kind",kind);op.put("path",relative);op.put("action",deleted?"delete":"put");op.put("device_id",baseline?"migration":device);op.put("issued_at",baseline?"":java.time.Instant.now().toString());
        if(!deleted) {op.put("record",updated);if(media!=null)op.put("blob",hash(media));}
        validate(op);
        if(media!=null&&!deleted)immutable(new File(root,"blobs/"+op.getString("blob")+".blob"),media);
        immutable(new File(root,"ops/"+revision+".op"),canonical(op).getBytes(StandardCharsets.UTF_8));
        if(captureOperations!=null)captureOperations.put(revision,op);
        return updated;
    }
    static List<File> sources(File root,boolean media) throws IOException {
        List<File> result=new ArrayList<>();if(!root.exists())return result;
        try(java.util.stream.Stream<Path> stream=Files.walk(root.toPath())) {
            stream.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).forEach(p->{
                String relative=root.toPath().relativize(p).toString().replace(File.separatorChar,'/');
                for(String part:relative.split("/"))if(part.startsWith("."))return;
                String name=p.getFileName().toString().toLowerCase(Locale.ROOT);
                if(media?name.matches(".*\\.(jpg|jpeg|mp4)$"):name.endsWith(".json")&&!Arrays.asList("records.json","cb.json","desktop-index.json").contains(name))result.add(p.toFile());
            });
        }result.sort(Comparator.comparing(File::getPath));return result;
    }
    static List<JSONObject> records(File file) throws Exception {
        String raw=LocalData.read(file);if(raw.trim().equals("[]"))return new ArrayList<>();return BoxRecords.parseRecords(raw);
    }
    void capture() throws Exception {
        captureOperations=operations();
        try{captureImpl();}finally{captureOperations=null;}
        checksums();
    }
    private void captureImpl() throws Exception {
        Map<String,JSONObject> operations=operations();
        for(String kind:new String[]{"record","media"}) {
            boolean media=kind.equals("media");File folder=media?images:data;
            List<File> candidates=sources(folder,media);
            if(!media&&!legacyData.equals(data))candidates.addAll(sources(legacyData,false));
            Set<String> seenPaths=new HashSet<>();
            for(File file:candidates){String relative=(media?images:recordRoot(file)).toPath().relativize(file.toPath()).toString().replace(File.separatorChar,'/');if(!seenPaths.add(relative))throw new IOException("Duplicate legacy/primary sync path: "+relative);}
            for(File file:candidates) {
                String relative=(media?images:recordRoot(file)).toPath().relativize(file.toPath()).toString().replace(File.separatorChar,'/');
                List<JSONObject> records=media?new ArrayList<>(Collections.singletonList(metadata.read(file))):records(file);
                String blob=media?hash(file):"";boolean changed=false;
                for(int i=0;i<records.size();i++) {
                    JSONObject record=records.get(i);String ident=record.optString("id","");if(ident.isEmpty())ident=legacyId(kind,relative,i);
                    JSONObject previous=operations.get(record.optString("revision","")),same=null;
                    for(JSONObject op:operations.values()) {
                        if(previous!=null&&op!=previous)continue;
                        if(!ident.equals(op.getString("id"))||!op.getString("action").equals("put"))continue;
                        if(canonical(clean(op.getJSONObject("record"))).equals(canonical(clean(record)))&&relative.equals(op.getString("path"))&&(!media||blob.equals(op.optString("blob"))||blob.equals(materialized().optString(op.getString("revision"))))) {same=op;break;}
                    }
                    if(same!=null) {if(!media&&!record.has("id")) {record.put("id",ident);record.put("revision",same.getString("revision"));changed=true;}continue;}
                    records.set(i,issue(kind,relative,record,media?file:null,false,null,i));changed|=!media;operations=operations();
                }
                if(changed)atomic(file,(canonical(new JSONArray(records))+"\n").getBytes(StandardCharsets.UTF_8));
            }
        }
    }
    void embedMedia(File file) throws Exception {
        String relative=images.toPath().relativize(file.toPath()).toString().replace(File.separatorChar,'/');
        for(List<JSONObject> ops:heads(operations()).values())if(ops.size()==1){JSONObject op=ops.get(0);
            if(op.getString("kind").equals("media")&&op.getString("path").equals(relative)&&op.getString("action").equals("put")&&metadata!=null){metadata.write(file,op.getJSONObject("record"));remember(op,file);return;}
        }
    }
    JSONObject materialized() throws Exception {
        File file=new File(root,"materialized.json");return file.exists()?new JSONObject(LocalData.read(file)):new JSONObject();
    }
    void remember(JSONObject op,File file) throws Exception {
        JSONObject cache=materialized();cache.put(op.getString("revision"),hash(file));atomic(new File(root,"materialized.json"),canonical(cache).getBytes(StandardCharsets.UTF_8));
    }
    File recordRoot(File file) throws IOException {
        if(file.toPath().startsWith(data.toPath()))return data;
        if(file.toPath().startsWith(legacyData.toPath()))return legacyData;
        throw new IOException("Record outside configured folders");
    }
    File safePath(String kind,String relative) throws IOException {
        File folder=kind.equals("media")?images:data;
        if(kind.equals("record")&&!new File(folder,relative).exists()&&new File(legacyData,relative).exists())folder=legacyData;
        File path=new File(folder,relative);
        if(!path.getCanonicalFile().toPath().startsWith(folder.getCanonicalFile().toPath()))throw new IOException("Escaping sync path");
        for(File p=path;p!=null&&!p.equals(folder.getParentFile());p=p.getParentFile())if(Files.isSymbolicLink(p.toPath()))throw new IOException("Symlink refused");
        return path;
    }
    void trash(File file) throws IOException {
        File target=new File(root,"trash/"+UUID.randomUUID()+"/"+file.getName());Files.createDirectories(target.getParentFile().toPath());
        Files.move(file.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);
    }
    Map<String,List<JSONObject>> apply() throws Exception {
        Map<String,JSONObject> operations=operations();Map<String,List<JSONObject>> heads=heads(operations),conflicts=new TreeMap<>();
        for(Map.Entry<String,List<JSONObject>> entry:heads.entrySet())if(entry.getValue().size()>1&&!entry.getValue().get(0).getString("kind").equals("device"))conflicts.put(entry.getKey(),entry.getValue());
        JSONObject conflictJson=new JSONObject();for(Map.Entry<String,List<JSONObject>> e:conflicts.entrySet())conflictJson.put(e.getKey(),new JSONArray(e.getValue()));
        atomic(new File(root,"conflicts.json"),(canonical(conflictJson)+"\n").getBytes(StandardCharsets.UTF_8));
        Map<String,String> destinations=new HashMap<>();
        for(Map.Entry<String,List<JSONObject>> e:heads.entrySet())if(e.getValue().size()==1){JSONObject op=e.getValue().get(0);if(op.getString("kind").equals("media")&&op.getString("action").equals("put")){String previous=destinations.put(op.getString("path"),e.getKey());if(previous!=null&&!previous.equals(e.getKey()))throw new IOException("Media filename conflict; rename one version: "+op.getString("path"));}}
        for(Map.Entry<String,List<JSONObject>> entry:heads.entrySet()) {
            if(conflicts.containsKey(entry.getKey()))continue;JSONObject op=entry.getValue().get(0);
            if(!op.getString("kind").equals("media"))continue;
            Set<String> known=new HashSet<>();for(JSONObject old:operations.values())if(entry.getKey().equals(old.getString("id"))&&old.has("blob")){known.add(old.getString("blob"));known.add(materialized().optString(old.getString("revision")));}
            for(JSONObject old:operations.values()) {
                if(!entry.getKey().equals(old.getString("id")))continue;
                if(op.getString("action").equals("put")&&old.getString("path").equals(op.getString("path")))continue;
                File file=safePath("media",old.getString("path"));if(file.exists()&&known.contains(hash(file)))trash(file);
            }
            if(op.getString("action").equals("put")) {
                File blob=new File(root,"blobs/"+op.getString("blob")+".blob");if(!hash(blob).equals(op.getString("blob")))throw new IOException("Corrupt media payload");
                File target=safePath("media",op.getString("path"));
                if(target.exists()&&!hash(target).equals(op.getString("blob"))&&!hash(target).equals(materialized().optString(op.getString("revision")))) {if(!known.contains(hash(target)))throw new IOException("Untracked file collision: "+target);trash(target);}
                if(!target.exists())immutable(target,blob);
                if(metadata!=null&&!hash(target).equals(materialized().optString(op.getString("revision")))){metadata.write(target,op.getJSONObject("record"));remember(op,target);}
            }
        }
        Set<String> paths=new TreeSet<>();for(JSONObject op:operations.values())if(op.getString("kind").equals("record"))paths.add(op.getString("path"));
        for(String relative:paths) {
            File file=safePath("record",relative);List<JSONObject> existing=file.exists()?records(file):new ArrayList<>(),keep=new ArrayList<>();
            for(int i=0;i<existing.size();i++) {JSONObject r=existing.get(i);String ident=r.optString("id",legacyId("record",relative,i));if(!heads.containsKey(ident)||conflicts.containsKey(ident))keep.add(r);}
            for(Map.Entry<String,List<JSONObject>> entry:heads.entrySet()) {
                if(conflicts.containsKey(entry.getKey()))continue;JSONObject op=entry.getValue().get(0);
                if(op.getString("kind").equals("record")&&op.getString("path").equals(relative)&&op.getString("action").equals("put"))keep.add(op.getJSONObject("record"));
            }
            String before=canonical(new JSONArray(existing)),after=canonical(new JSONArray(keep));
            if(!before.equals(after)) {if(file.exists())trash(file);atomic(file,(after+"\n").getBytes(StandardCharsets.UTF_8));}
        }
        checksums();
        return conflicts;
    }
    void delete(File file,int index) throws Exception {
        if(Files.isSymbolicLink(file.toPath()))throw new IOException("Symlink refused");
        if(index>=0&&index>=records(file).size())throw new IOException("The selected record no longer exists");
        capture();
        if(index>=0) {JSONObject r=records(file).get(index);issue("record",recordRoot(file).toPath().relativize(file.toPath()).toString().replace(File.separatorChar,'/'),r,null,true,null,index);}
        else {String relative=images.toPath().relativize(file.toPath()).toString().replace(File.separatorChar,'/');JSONObject chosen=null;
            for(List<JSONObject> ops:heads(operations()).values())for(JSONObject op:ops)if(op.getString("kind").equals("media")&&op.getString("path").equals(relative)&&op.getString("action").equals("put"))chosen=op;
            if(chosen==null)throw new IOException("Media is not registered");issue("media",relative,chosen.getJSONObject("record"),null,true,null,0);
        }apply();
    }
    void acknowledge() throws Exception {
        Map<String,JSONObject> operations=operations();Map<String,List<JSONObject>> heads=heads(operations);
        List<String> seen=new ArrayList<>();for(Map.Entry<String,JSONObject> entry:operations.entrySet())if(!entry.getValue().getString("kind").equals("device"))seen.add(entry.getKey());Collections.sort(seen);
        JSONArray array=new JSONArray(seen);
        List<JSONObject> previous=heads.getOrDefault(device,Collections.emptyList());
        for(JSONObject op:previous)if(op.getString("action").equals("retire"))throw new IOException("This device was retired; register a new device identity before synchronizing");
        for(JSONObject op:previous)if(canonical(op.getJSONArray("seen")).equals(canonical(array)))return;
        JSONArray parents=new JSONArray();for(JSONObject op:previous)parents.put(op.getString("revision"));
        JSONObject op=new JSONObject();op.put("schema",1);op.put("id",device);op.put("revision",UUID.randomUUID().toString());op.put("parents",parents);op.put("kind","device");op.put("path","devices/"+device+".json");op.put("action","ack");op.put("device_id",device);op.put("seen",array);op.put("issued_at",java.time.Instant.now().toString());validate(op);
        immutable(new File(root,"ops/"+op.getString("revision")+".op"),canonical(op).getBytes(StandardCharsets.UTF_8));
    }
    void deleteBox(File file) throws Exception {
        capture();String relative=recordRoot(file).toPath().relativize(file.toPath()).toString().replace(File.separatorChar,'/');
        for(JSONObject record:records(file))issue("record",relative,record,null,true,null,0);apply();
    }
    void restore(String id,String revision) throws Exception {
        capture();
        JSONObject op=operations().get(revision);
        if(op==null||!op.getString("id").equals(id)||!op.getString("action").equals("put"))throw new IOException("Choose a historical put revision");
        JSONArray parents=new JSONArray();for(JSONObject head:heads(operations()).getOrDefault(id,Collections.emptyList()))parents.put(head.getString("revision"));
        File blob=op.has("blob")?new File(root,"blobs/"+op.getString("blob")+".blob"):null;
        issue(op.getString("kind"),op.getString("path"),op.getJSONObject("record"),blob,false,parents,0);apply();
    }
    void resolve(String id,String revision) throws Exception {
        capture();
        List<JSONObject> heads=heads(operations()).getOrDefault(id,Collections.emptyList());JSONObject chosen=null;JSONArray parents=new JSONArray();
        for(JSONObject op:heads){parents.put(op.getString("revision"));if(revision.equals(op.getString("revision")))chosen=op;}
        if(chosen==null)throw new IOException("Choose an existing conflict revision");
        JSONObject record=chosen.optJSONObject("record");if(record==null) {record=new JSONObject();record.put("id",id);record.put("revision",revision);}
        File blob=chosen.has("blob")?new File(root,"blobs/"+chosen.getString("blob")+".blob"):null;
        // Resolution reuses immutable payload bytes; do not rewrite a blob's metadata.
        issue(chosen.getString("kind"),chosen.getString("path"),record,blob,chosen.getString("action").equals("delete"),parents,0);apply();
    }
}
