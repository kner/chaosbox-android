package local.sshcopy;
import java.nio.file.*;
import java.io.*;
import java.util.*;

public class SourceFilesTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("sshcopy-test-");
        try {
            Files.write(root.resolve("ordinary.txt"), new byte[]{1});
            Files.write(root.resolve(".hidden"), new byte[]{2});
            Files.createDirectory(root.resolve("subdirectory"));
            Files.write(root.resolve("subdirectory/nested.txt"), new byte[]{3});
            Files.createSymbolicLink(root.resolve("link"), root.resolve("ordinary.txt"));
            String[] names = Arrays.stream(SourceFiles.list(root.toFile())).map(File::getName).toArray(String[]::new);
            if (!Arrays.equals(names, new String[]{".hidden", "ordinary.txt"})) throw new AssertionError(Arrays.toString(names));
            Files.createDirectory(root.resolve("empty"));
            if (SourceFiles.list(root.resolve("empty").toFile()).length != 0) throw new AssertionError("Empty directory");
            try { SourceFiles.list(root.resolve("missing").toFile()); throw new AssertionError("Missing folder accepted"); }
            catch (IOException expected) {}
            System.out.println("PASS: top-level files, hidden files, excluded subdirectories/symlinks, empty/missing directories");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch(IOException e) { throw new RuntimeException(e); } });
            }
        }
    }
}
