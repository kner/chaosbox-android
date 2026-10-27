package local.sshcopy;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class InstallationResetTest {
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        Path storage=Files.createTempDirectory("installation-reset-");
        try {
            File first=InstallationFiles.root(storage.toFile(),"KBox-Bilder");
            require(first.getName().equals("KBox-Bilder"),"Named installation directory changed");
            require(!InstallationFiles.root(storage.toFile(),"ChaosBox-default").getName().equalsIgnoreCase(InstallationFiles.root(storage.toFile(),"chaosbox").getName()),"Default and named roots alias on case-insensitive storage");
            File other=InstallationFiles.root(storage.toFile(),"work");
            Files.createDirectories(other.toPath());Files.writeString(new File(other,"setup.ini").toPath(),"[SSH]\nInstallation=old-other\n");
            Files.createDirectories(storage.resolve("ChaosBox/Setup"));Files.writeString(storage.resolve("ChaosBox/Setup/setup.ini"),"[SSH]\nInstallation=old-shared\n");
            String defaults="[App.Chaosbox]\nTitel=Chaosbox\n[SSH]\nHost=server\n";
            require(InstallationFiles.setup(first,defaults).equals(defaults),"Shared setup resurrected");
            String oldGeneration=InstallationFiles.generation(first);
            Files.createDirectories(first.toPath());Files.writeString(new File(first,"setup.ini").toPath(),"[SSH]\nInstallation=old\n");
            require(InstallationFiles.setup(first,defaults).contains("Installation=old"),"Existing setup lost");
            try(java.util.stream.Stream<Path> files=Files.walk(first.toPath())){for(Path path:files.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(path);}
            require(InstallationFiles.setup(first,defaults).equals(defaults),"Deleted installation restored old target");
            require(!InstallationFiles.generation(first).equals(oldGeneration),"Deleted generation reused");
            AppProfiles config=AppProfiles.parse("[App.Chaosbox]\nJPG=/old/outside\nDaten=../external\n");
            StoragePaths paths=StoragePaths.forProfile(first,config.selected(""));
            require(paths.images.equals(new File(first,"Chaosbox/JPG")),"External images used");
            require(paths.data.equals(new File(first,"Chaosbox/TXT"))&&paths.legacyData.equals(paths.data),"External/legacy data used");
            Files.createSymbolicLink(storage.resolve("linked"),other.toPath());
            try{InstallationFiles.root(storage.toFile(),"linked");throw new AssertionError("Linked installation accepted");}catch(IOException expected){}
            System.out.println("PASS: delete/recreate Android installation stays empty and unbound; no shared cache, legacy data, external paths or cross-installation fallback");
        } finally {try(java.util.stream.Stream<Path> files=Files.walk(storage)){for(Path p:files.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(p);}}
    }
}
