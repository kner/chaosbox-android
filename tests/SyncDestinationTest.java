package local.sshcopy;
import java.util.*;
import java.io.IOException;
public final class SyncDestinationTest {
    public static void main(String[] args) throws Exception {
        if(args.length==2){Map<String,String> config=new HashMap<>();config.put("installation",args[0]);SyncDestination.select(config,args[1]);System.out.println(config.get("datadestination"));return;}
        Map<String,String> first=new HashMap<>();first.put("installation","workshop-1234");first.put("datadestination","chaosbox-data/TXT");
        SyncDestination.select(first,"ChaosBox");
        if(!first.get("datadestination").equals("l1/storage/installations/workshop-1234/profiles/chaosbox/TXT"))throw new AssertionError(first);
        Map<String,String> second=new HashMap<>();second.put("installation","private-5678");SyncDestination.select(second,"ChaosBox");
        if(first.get("datadestination").equals(second.get("datadestination")))throw new AssertionError("Shared installation target");
        for(String target:new String[]{"","chaosbox-data/TXT","chaosbox-data/profiles/tools/TXT"}) {
            Map<String,String> config=new HashMap<>();config.put("datadestination",target);
            try{SyncDestination.select(config,"ChaosBox");throw new AssertionError("Shared default accepted");}catch(IOException expected){}
        }
        first.put("installation","../escape");
        try{SyncDestination.select(first,"ChaosBox");throw new AssertionError("Unsafe ID accepted");}catch(IOException expected){}
        second.put("remoteprofile","parts/electronics");SyncDestination.select(second,"Other");
        if(!second.get("datadestination").endsWith("/profiles/parts/electronics/TXT"))throw new AssertionError(second);
        second.put("webinstallation","../outside");
        try{SyncDestination.select(second,"Other");throw new AssertionError("Unsafe web root accepted");}catch(IOException expected){}
        System.out.println("PASS: explicit installation selection, isolated targets, profile override and unsafe/shared default rejection");
    }
}
