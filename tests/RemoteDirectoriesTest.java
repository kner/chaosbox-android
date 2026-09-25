package local.sshcopy;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;
import java.util.*;

public final class RemoteDirectoriesTest {
    public static void main(String[] args) throws Exception {
        Fake server = new Fake();
        List<String> log = new ArrayList<>();
        RemoteFiles.ensureDirectory(server, "/home/user", "l1/storage/app/exif/jpg/teile", log::add);
        check(server.current.equals("/home/user/l1/storage/app/exif/jpg/teile"), "relative destination");
        check(server.created.size() == 6 && log.size() == 6, "all missing parents created and reported");
        RemoteFiles.ensureDirectory(server, "/home/user", "l1/storage/app/exif/jpg/teile", log::add);
        check(server.created.size() == 6, "existing folders reused");
        RemoteFiles.ensureDirectory(server, "/home/user", "/srv//cloud/./data", log::add);
        check(server.current.equals("/srv/cloud/data"), "absolute path");
        RemoteFiles.ensureDirectory(server, "/home/user", "l1/Teile * ?", log::add);
        check(server.current.endsWith("/Teile * ?"), "literal special characters");
        int before = server.created.size();
        try {
            RemoteFiles.ensureDirectory(server, "/home/user", "new/../escape", log::add);
            throw new AssertionError("parent traversal accepted");
        } catch (SftpException expected) { }
        check(server.created.size() == before, "invalid path rejected before creating folders");
        for (int failure : new int[]{SSH_FX_PERMISSION_DENIED, SSH_FX_CONNECTION_LOST, SSH_FX_FAILURE}) {
            server.cdFailure = failure;
            expectFailure(server, failure, log);
            check(server.created.size() == before, "cd error must not trigger mkdir");
        }
        server.cdFailure = 0;
        server.mkdirFailure = SSH_FX_PERMISSION_DENIED;
        expectFailure(server, SSH_FX_PERMISSION_DENIED, log);
        server.mkdirFailure = 0;
        server.race = true;
        RemoteFiles.ensureDirectory(server, "/home/user", "new", log::add);
        check(server.current.equals("/home/user/new"), "concurrent creation accepted");
        System.out.println("PASS: recursive destination creation, existing directories, absolute paths, escaping, errors and concurrent creation");
    }
    private static final int SSH_FX_PERMISSION_DENIED = ChannelSftp.SSH_FX_PERMISSION_DENIED;
    private static final int SSH_FX_CONNECTION_LOST = ChannelSftp.SSH_FX_CONNECTION_LOST;
    private static final int SSH_FX_FAILURE = ChannelSftp.SSH_FX_FAILURE;
    private static void expectFailure(Fake server, int code, List<String> log) throws Exception {
        try {
            RemoteFiles.ensureDirectory(server, "/home/user", "new", log::add);
            throw new AssertionError("server failure swallowed");
        } catch (SftpException expected) { check(expected.id == code, "original server error preserved"); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class Fake extends ChannelSftp {
        String current = "/home/user";
        Set<String> directories = new HashSet<>(Arrays.asList("/", "/home/user"));
        List<String> created = new ArrayList<>();
        int cdFailure, mkdirFailure;
        boolean race;
        String resolve(String value) { return value.startsWith("/") ? value : (current.equals("/") ? "/" : current + "/") + value; }
        @Override public void cd(String value) throws SftpException {
            if (cdFailure != 0 && !value.equals("/home/user")) throw new SftpException(cdFailure, "unavailable");
            String path = resolve(value.replace("\\*", "*").replace("\\?", "?").replace("\\\\", "\\"));
            if (!directories.contains(path)) throw new SftpException(SSH_FX_NO_SUCH_FILE, "missing");
            current = path;
        }
        @Override public void mkdir(String value) throws SftpException {
            if (mkdirFailure != 0) throw new SftpException(mkdirFailure, "creation denied");
            String path = resolve(value);
            directories.add(path);
            if (race) throw new SftpException(SSH_FX_FAILURE, "already created by another client");
            created.add(path);
        }
    }
}
