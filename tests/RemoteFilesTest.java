package local.sshcopy;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

public final class RemoteFilesTest {
    public static void main(String[] args) throws Exception {
        if (!RemoteFiles.isNewer(201000, 200)) throw new AssertionError("Newer local file must upload");
        if (RemoteFiles.isNewer(200999, 200)) throw new AssertionError("Equal SFTP timestamp must skip");
        if (RemoteFiles.isNewer(199000, 200)) throw new AssertionError("Newer server file must be preserved");
        SftpException missing = new SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "No such file");
        if (RemoteFiles.shouldSkip(failing(missing), "new-photo.jpg", 123)) {
            throw new AssertionError("New photo must be uploaded");
        }
        for (int code : new int[]{ChannelSftp.SSH_FX_PERMISSION_DENIED, ChannelSftp.SSH_FX_FAILURE,
                ChannelSftp.SSH_FX_CONNECTION_LOST}) {
            SftpException failure = new SftpException(code, "Server failure");
            try {
                RemoteFiles.shouldSkip(failing(failure), "photo.jpg", 123);
                throw new AssertionError("Server error was swallowed: " + code);
            } catch (SftpException actual) {
                if (actual != failure) throw new AssertionError("Original failure lost");
            }
        }
        System.out.println("PASS: missing remote photo uploads; permission, server and connection errors propagate");
    }

    private static ChannelSftp failing(SftpException failure) {
        return new ChannelSftp() {
            @Override public SftpATTRS lstat(String path) throws SftpException {
                throw failure;
            }
        };
    }
}
