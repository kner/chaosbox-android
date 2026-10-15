package local.sshcopy;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/** Android explicitly joins one Desktop installation; it never guesses a shared target. */
final class SyncDestination {
    static void select(Map<String,String> settings,String profile) throws IOException {
        String installation=settings.getOrDefault("installation","").trim();
        if(!installation.isEmpty()) {
            if(!installation.matches("[a-z0-9][a-z0-9_-]{0,79}"))throw new IOException("Invalid installation ID");
            String selected=settings.getOrDefault("remoteprofile",profile).toLowerCase(Locale.ROOT);
            for(String part:selected.split("/",-1))if(part.isEmpty()||part.startsWith(".")||part.contains("\\")||part.matches(".*[\\p{Cntrl}].*"))
                throw new IOException("Invalid remote profile");
            String root="chaosbox-data/installations/"+installation+"/profiles/"+selected;
            settings.put("datadestination",root+"/TXT");settings.put("sftpdatadestination",root+"/TXT");settings.put("imagedestination",root+"/JPG");
        }else {
            String destination=settings.getOrDefault("sftpdatadestination",settings.getOrDefault("datadestination",""));
            if(destination.isEmpty()||destination.equals("chaosbox-data/TXT")||destination.startsWith("chaosbox-data/profiles/"))
                throw new IOException("Select a Desktop installation: set Installation in [SSH] in ChaosBox/Setup/setup.ini. The installation ID is shown by Desktop synchronization.");
            settings.put("datadestination",destination);
        }
    }
}
