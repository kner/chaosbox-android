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
            String web=settings.getOrDefault("webinstallation","l1").trim();
            for(String part:web.split("/",-1))if(!part.matches("[A-Za-z0-9][A-Za-z0-9_.-]*"))throw new IOException("Invalid web installation directory");
            String root=web+"/storage/installations/"+installation+"/profiles/"+selected;
            settings.put("datadestination",root+"/TXT");settings.put("sftpdatadestination",root+"/TXT");settings.put("imagedestination",root+"/JPG");
        }else {
            throw new IOException("Fresh installation: set Installation in this installation's setup.ini to explicitly join a Desktop installation.");
        }
    }
}
