package local.sshcopy;
import java.util.*;
import java.io.IOException;
public final class SyncDestinationTest {
    public static void main(String[] args) throws Exception {
        Map<String,String> first=new HashMap<>();first.put("installation","workshop-1234");first.put("datadestination","chaosbox-data/TXT");
        SyncDestination.select(first,"ChaosBox");
        if(!first.get("datadestination").equals("chaosbox-data/installations/workshop-1234/profiles/chaosbox/TXT"))throw new AssertionError(first);
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
        System.out.println("PASS: explicit installation selection, isolated targets, profile override and unsafe/shared default rejection");
    }
}
