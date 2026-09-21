package local.sshcopy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Arrays;
import java.util.Comparator;

final class SourceFiles {
    static File[] list(File source) throws IOException {
        File[] files = source.listFiles(f -> Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS));
        if (files == null) throw new IOException("Cannot read source folder: " + source);
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }
}
