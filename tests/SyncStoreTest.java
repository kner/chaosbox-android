package local.sshcopy;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** CLI used by the Python interoperability tests without an Android runtime. */
public final class SyncStoreTest {
    public static void main(String[] args) throws Exception {
        File root=new File(args[1]);
        SyncStore store=new SyncStore(new File(root,"JPG"),new File(root,"TXT"),new SyncStore.Metadata(){
            public JSONObject read(File file) throws Exception {
                String raw=Mp4Comments.isVideo(file.getName())?Mp4Comments.read(new FileInputStream(file)):XmpRecords.read(Files.readAllBytes(file.toPath()),"");
                if(raw.isEmpty()){JSONObject record=new JSONObject();record.put("comment","");return record;}
                return new JSONObject(raw);
            }
            public void write(File file,JSONObject record) throws Exception {if(Mp4Comments.isVideo(file.getName()))Mp4Comments.write(file,record.toString());else SyncStore.atomic(file,XmpRecords.write(Files.readAllBytes(file.toPath()),record.toString()));}
        });
        switch(args[0]) {
            case "capture": store.capture(); break;
            case "checksums": System.out.println(SyncStore.canonical(store.checksums())); break;
            case "apply": System.out.println(store.apply().size()); break;
            case "delete": store.delete(new File(store.data,args[2]),Integer.parseInt(args[3]));break;
            case "delete-media":store.delete(new File(store.images,args[2]),-1);break;
            case "restore":store.restore(args[2],args[3]);break;
            case "resolve":store.resolve(args[2],args[3]);break;
            case "edit": {
                File file=new File(store.data,args[2]);JSONArray records=new JSONArray(LocalData.read(file));
                records.getJSONObject(Integer.parseInt(args[3])).put("comment",args[4]);
                SyncStore.atomic(file,SyncStore.canonical(records).getBytes(StandardCharsets.UTF_8));store.capture();break;
            }
            case "edit-media": {
                File file=new File(store.images,args[2]);JSONObject record=store.metadata.read(file);record.put("comment",args[3]);store.metadata.write(file,record);store.capture();break;
            }
            default: throw new IllegalArgumentException("Unknown operation");
        }
    }
}
