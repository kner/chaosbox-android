package local.sshcopy;

import com.jcraft.jsch.SftpException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** User-visible diagnostics shared by transfer and foreground UI. */
final class UploadErrors {
    static String describe(Throwable error) {
        StringBuilder details = new StringBuilder();
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null || message.trim().isEmpty()) message = cause.getClass().getSimpleName();
            if (cause instanceof UnknownHostException) message = "Could not resolve server name: " + message;
            else if (cause instanceof SocketTimeoutException) message = "Connection timed out: " + message;
            else if (cause instanceof SftpException) {
                int code = ((SftpException) cause).id;
                String label = code == 3 ? "Server access denied"
                        : code == 2 ? "Server file or destination folder is missing"
                        : code == 6 || code == 7 ? "Connection to server lost" : "SFTP error";
                message = label + " (Code " + code + "): " + message;
            }
            if (details.indexOf(message) < 0) {
                if (details.length() > 0) details.append("\n");
                details.append(message);
            }
        }
        return details.toString();
    }
}
