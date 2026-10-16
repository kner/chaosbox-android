package local.sshcopy;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Regression test: different journal identities sharing a media filename, including absent files. */
public final class MediaFilenameConflictTest {
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static SyncStore store(File root) throws Exception {return new SyncStore(new File(root,"JPG"),new File(root,"TXT"),null);}
    static void fixture(SyncStore store,String path,String id,String bytes) throws Exception {
        File source=File.createTempFile("payload-",".jpg");
        try {Files.write(source.toPath(),bytes.getBytes("UTF-8"));JSONObject record=new JSONObject();record.put("id",id);
            store.issue("media",path,record,source,false,new JSONArray(),0);
        }finally{source.delete();}
    }
    public static void main(String[] args) throws Exception {
        File root=new File(args[0]);String path="klebebänder/2026-10-14-15.31.44_cb.jpg";
        String first="00000000-0000-4000-8000-000000000001",second="00000000-0000-4000-8000-000000000002";
        for(boolean exists:new boolean[]{false,true}) {
            SyncStore store=store(new File(root,"case-"+exists));fixture(store,path,first,"one");fixture(store,path,second,"two");
            if(exists)SyncStore.atomic(new File(store.images,path),"two".getBytes("UTF-8"));
            int before=store.operations().size();require(store.mediaFilenameConflicts().size()==1,"not detected");
            require(store.repairMediaFilenames()==1,"wrong rename count");
            require(store.operations().size()==before+1,"history rewritten");
            require(store.mediaFilenameConflicts().isEmpty(),"collision remains");
            Map<String,List<JSONObject>> heads=store.heads(store.operations());
            require(heads.size()==2,"identity lost");
            require(heads.get(first).get(0).getString("path").equals(path),"keeper changed");
            JSONObject renamed=heads.get(second).get(0);
            require(renamed.getString("path").contains("_conflict_"),"suffix missing");
            require(renamed.getJSONArray("parents").length()==1,"ancestry lost");
            require(LocalData.read(new File(store.images,path)).equals("one"),"keeper bytes lost");
            require(LocalData.read(new File(store.images,renamed.getString("path"))).equals("two"),"renamed bytes lost");
            store.apply();require(new File(store.images,path).isFile(),"repeat apply removed keeper");
            require(store.repairMediaFilenames()==0,"repair not idempotent");
        }
        SyncStore untracked=store(new File(root,"untracked"));fixture(untracked,path,first,"one");fixture(untracked,path,second,"two");
        SyncStore.atomic(new File(untracked.images,path),"untracked".getBytes("UTF-8"));
        try{untracked.repairMediaFilenames();throw new AssertionError("untracked data overwritten");}catch(IOException expected){}
        require(LocalData.read(new File(untracked.images,path)).equals("untracked"),"untracked data changed");
        require(untracked.operations().size()==2,"failed repair changed journal");
        System.out.println("PASS: filename conflict repair keeps identities, history and both payloads; missing files, idempotence and untracked-file protection");
    }
}
