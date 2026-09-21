package local.sshcopy;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

final class RemoteFiles {
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
