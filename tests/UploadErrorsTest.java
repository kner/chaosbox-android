package local.sshcopy;

import com.jcraft.jsch.SftpException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

public final class UploadErrorsTest {
    public static void main(String[] args) {
        String denied = UploadErrors.describe(new IOException("File photo.jpg → jpg/teile",
                new SftpException(3, "Permission denied")));
        require(denied, "photo.jpg");
        require(denied, "jpg/teile");
        require(denied, "Server access denied");
        require(denied, "Code 3");
        require(UploadErrors.describe(new UnknownHostException("example.invalid")), "Could not resolve server name");
        require(UploadErrors.describe(new SocketTimeoutException()), "Connection timed out");
        require(UploadErrors.describe(new IOException()), "IOException");
        require(UploadErrors.describe(new SftpException(7, "Lost")), "Connection to server lost");
        require(UploadErrors.describe(new SftpException(2, "Missing")), "destination folder is missing");
        String collision = UploadErrors.describe(new IOException("Untracked file collision: /JPG/photo.jpg"));
        require(collision, "/JPG/photo.jpg");
        require(collision, "copy the existing file to a backup");
        require(collision, "Your options:");
        if (!UploadErrors.describe(new IOException(collision)).equals(collision)) throw new AssertionError("Duplicate guidance");
        require(UploadErrors.describe(new IOException("Immutable operation collision")), "do not edit or delete journal");
        require(UploadErrors.describe(new IOException("Unexpected problem")), "earlier steps may have completed");
        System.out.println("PASS: upload diagnostics preserve file, destination, server error, DNS and timeout causes");
    }
    private static void require(String actual, String expected) {
        if (!actual.contains(expected)) throw new AssertionError(actual + " missing " + expected);
    }
}
