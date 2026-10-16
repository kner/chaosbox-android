package local.sshcopy;

import java.io.*;
import java.nio.file.Files;

/** One installation owns one directory, with no shared-folder or private-cache fallback. */
final class InstallationFiles {
    static File root(File storage,String name) throws IOException {
        if(!name.equals("ChaosBox-default")&&!name.matches("[A-Za-z][A-Za-z0-9_]{0,39}"))throw new IOException("Invalid installation directory");
        File base=storage.getCanonicalFile(),root=new File(base,name);
        if(Files.isSymbolicLink(root.toPath())||!root.getCanonicalFile().getParentFile().equals(base))
            throw new IOException("Installation directory must not be a link or escape storage");
        return root;
    }
    static File owned(File root,String relative) throws IOException {
        File base=root.getCanonicalFile(),file=new File(base,relative).getCanonicalFile();
        if(!file.toPath().startsWith(base.toPath())||file.equals(base))throw new IOException("Path escapes installation: "+relative);
        return file;
    }
    static String generation(File root) throws IOException {
        Files.createDirectories(root.toPath());
        File identity=owned(root,".installation-id");
        if(!identity.exists()) {
            java.nio.file.Path temporary=Files.createTempFile(root.toPath(),".identity-",".tmp");
            try {
                Files.write(temporary,(java.util.UUID.randomUUID().toString()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                try{Files.createLink(identity.toPath(),temporary);}catch(java.nio.file.FileAlreadyExistsException ignored){}
                catch(UnsupportedOperationException|java.nio.file.FileSystemException unsupported) {
                    // Shared Android storage may not support hard links. CREATE_NEW never overwrites an identity.
                    try{Files.write(identity.toPath(),Files.readAllBytes(temporary),java.nio.file.StandardOpenOption.CREATE_NEW);}catch(java.nio.file.FileAlreadyExistsException ignored){}
                }
            }finally{Files.deleteIfExists(temporary);}
        }
        return existingGeneration(root);
    }
    static String existingGeneration(File root) throws IOException {
        File identity=owned(root,".installation-id");
        try{return java.util.UUID.fromString(LocalData.read(identity).trim()).toString();}
        catch(IllegalArgumentException e){throw new IOException("Invalid installation identity",e);}
    }
    static String setup(File root,String defaults) throws IOException {
        File setup=new File(root,"setup.ini");
        if(Files.isSymbolicLink(setup.toPath()))throw new IOException("Setup symlink refused");
        // A deleted directory is a fresh install. Never consult old directories or cached settings.
        return setup.isFile()?LocalData.read(setup):defaults;
    }
}
