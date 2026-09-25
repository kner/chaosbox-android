package local.sshcopy;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

final class RemoteFiles {
    /** Enter a destination, creating every missing component from the login directory or root. */
    static void ensureDirectory(ChannelSftp sftp, String home, String destination,
            java.util.function.Consumer<String> log) throws SftpException {
        String[] parts = destination.split("/");
        for (String part : parts) {
            if (part.equals("..")) throw new SftpException(ChannelSftp.SSH_FX_FAILURE,
                    "Parent paths are not allowed as upload destinations: " + destination);
        }
        sftp.cd(destination.startsWith("/") ? "/" : escape(home));
        String path = destination.startsWith("/") ? "/" : "";
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            path += (path.isEmpty() || path.endsWith("/") ? "" : "/") + part;
            try {
                sftp.cd(escape(part));
            } catch (SftpException missing) {
                if (missing.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw missing;
                try {
                    sftp.mkdir(part);
                    log.accept("Destination folder created: " + path);
                } catch (SftpException creation) {
                    // Another client may have created the folder since our first attempt.
                    try { sftp.cd(escape(part)); }
                    catch (SftpException stillUnavailable) { throw creation; }
                    continue;
                }
                sftp.cd(escape(part));
            }
        }
    }

    private static String escape(String name) {
        return name.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }

    static boolean isNewer(long localModifiedMillis, long remoteSeconds) {
        return localModifiedMillis / 1000 > remoteSeconds;
    }

    static boolean shouldSkip(ChannelSftp sftp, String remote, long localModifiedMillis) throws SftpException {
        try {
            SftpATTRS attrs = sftp.lstat(remote);
            if (!attrs.isReg()) {
                throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "Remote target is not a regular file: " + remote);
            }
            return !isNewer(localModifiedMillis, Integer.toUnsignedLong(attrs.getMTime()));
        } catch (SftpException e) {
            // A new photo has no remote entry yet. Other failures must stop the transfer.
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                return false;
            }
            throw e;
        }
    }
}
