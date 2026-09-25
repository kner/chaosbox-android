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
            if (cause instanceof UnknownHostException) message = "Servername konnte nicht aufgelöst werden: " + message;
            else if (cause instanceof SocketTimeoutException) message = "Zeitüberschreitung bei der Verbindung: " + message;
            else if (cause instanceof SftpException) {
                int code = ((SftpException) cause).id;
                String label = code == 3 ? "Zugriff auf dem Server verweigert"
                        : code == 2 ? "Datei oder Zielordner auf dem Server fehlt"
                        : code == 6 || code == 7 ? "Verbindung zum Server unterbrochen" : "SFTP-Fehler";
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
