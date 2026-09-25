package local.sshcopy;

import com.jcraft.jsch.SftpException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

public final class UploadErrorsTest {
    public static void main(String[] args) {
        String denied = UploadErrors.describe(new IOException("Datei photo.jpg → jpg/teile",
                new SftpException(3, "Permission denied")));
        require(denied, "photo.jpg");
        require(denied, "jpg/teile");
        require(denied, "Zugriff auf dem Server verweigert");
        require(denied, "Code 3");
        require(UploadErrors.describe(new UnknownHostException("example.invalid")), "Servername konnte nicht aufgelöst werden");
        require(UploadErrors.describe(new SocketTimeoutException()), "Zeitüberschreitung");
        require(UploadErrors.describe(new IOException()), "IOException");
        require(UploadErrors.describe(new SftpException(7, "Lost")), "Verbindung zum Server unterbrochen");
        require(UploadErrors.describe(new SftpException(2, "Missing")), "Zielordner auf dem Server fehlt");
        System.out.println("PASS: upload diagnostics preserve file, destination, server error, DNS and timeout causes");
    }
    private static void require(String actual, String expected) {
        if (!actual.contains(expected)) throw new AssertionError(actual + " missing " + expected);
    }
}
