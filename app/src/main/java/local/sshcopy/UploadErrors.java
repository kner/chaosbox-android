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
        return explain(details.toString());
    }
    static String explain(String details) {
        if (details.contains("\n\nWhat happened:")) return details;
        String text = details.toLowerCase(java.util.Locale.ROOT);
        if (text.contains("untracked"))
            return details + "\n\nWhat happened: A destination file already exists, but its contents do not match a known synchronized version. KBox refused to overwrite or remove that file.\n\nYour options: Keep both: copy the existing file to a backup outside the synchronized JPG/TXT folders, verify the copy, then move the original out of the way and retry synchronization. Compare the downloaded version with your backup. To keep your local version, import it afterward as a separate item. Do not delete either copy before comparing them. For a remote file, perform these steps on the server.";
        if (text.contains("media filename conflict"))
            return details + "\n\nWhat happened: Two different media items use the same destination filename. Synchronization cannot choose which item should occupy that path.\n\nYour options: Keep both items under different filenames, then synchronize again. On Android, long press synchronization and choose Repair filename conflicts. Back up the affected files before resolving the conflict.";
        if (text.contains("immutable") || text.contains("incomplete journal") || text.contains("invalid journal") || text.contains("cyclic journal") || text.contains("cross-object") || text.contains("operation filename") || text.contains("invalid revision") || text.contains("unsupported sync operation") || text.contains("invalid device acknowledgment") || text.contains("invalid object action") || text.contains("identity mismatch") || text.contains("invalid blob") || text.contains("unexpected journal") || text.contains("checksum index too large"))
            return details + "\n\nWhat happened: The synchronization history is inconsistent or uses an unsupported format. Applying it could associate changes with the wrong item.\n\nYour options: Stop synchronization on all devices and back up .chaosbox-sync. Check that all apps are up to date. Recover the missing or damaged history from a known-good device or backup with technical assistance; do not edit or delete journal entries to bypass this check.";
        if (text.contains("corrupt") || text.contains("digest mismatch") || text.contains("missing or corrupt"))
            return details + "\n\nWhat happened: A media payload is missing, incomplete, or does not match its recorded checksum.\n\nYour options: Check free space and connectivity, then retry the transfer. If the error repeats, preserve the journal and recover the payload from a known-good device or backup. Do not replace it with an unrelated file or delete the history.";
        if (text.contains("already running") || text.contains("transfer.lock") || text.contains("synchronization is busy"))
            return details + "\n\nWhat happened: Another synchronization owns the lock, or an interrupted run left a lock behind.\n\nYour options: Wait for the other synchronization to finish and retry. If it persists, stop all clients and ask for the lock to be checked. Remove a stale lock only after confirming no synchronization is running; never remove it during an active transfer.";
        if (text.contains("changed during") || text.contains("changed after") || text.contains("journal changed while"))
            return details + "\n\nWhat happened: A file or synchronization state changed while the operation was using it.\n\nYour options: Finish edits and stop other transfers, then retry. Some earlier items may already have completed; review the current files before repeating a batch operation.";
        if (text.contains("cancelled") || text.contains("canceled") || text.contains("transfer stopped"))
            return details + "\n\nWhat happened: The operation was interrupted before completion. Earlier steps may already have completed.\n\nYour options: Review the current state and start the operation again when ready. Synchronization can be retried; check completed items before repeating imports or other batch actions.";
        if (text.contains("host key") || text.contains("known_hosts") || text.contains("knownhosts") || text.contains("hostkey"))
            return details + "\n\nWhat happened: The server identity could not be verified using the configured trusted host keys.\n\nYour options: Confirm the server address and verify its host-key fingerprint through a trusted channel. Update KnownHosts only after verification. Do not disable host-key checking.";
        if (text.contains("auth fail") || text.contains("authentication") || text.contains("credentials") || text.contains("ssh key") || text.contains("private key"))
            return details + "\n\nWhat happened: The SSH credentials are missing, unreadable, or were rejected by the server.\n\nYour options: Check the configured user and KeyFile, and confirm that the matching public key is authorized on the server. Restore missing credentials from your secure backup; do not include private keys in an error report.";
        if (text.contains("permission denied") || text.contains("access denied") || text.contains("permissionerror"))
            return details + "\n\nWhat happened: The app does not have permission to access the requested file or server destination.\n\nYour options: Check access to the displayed path. For server errors, check the SSH account and destination ownership. On Android, grant the required file access in system settings. Retry after correcting permissions.";
        if (text.contains("no space left") || text.contains("disk full") || text.contains("enospc"))
            return details + "\n\nWhat happened: The destination has run out of storage space.\n\nYour options: Free space on the affected device or server, keeping your only copies and synchronization history intact, then retry.";
        if (text.contains("timed out") || text.contains("timeout") || text.contains("connection") || text.contains("resolve server") || text.contains("unknownhost") || text.contains("name or service not known") || text.contains("network") || text.contains("no route"))
            return details + "\n\nWhat happened: The server could not be reached reliably or the connection was interrupted.\n\nYour options: Check the network, server address, SSH port, and server availability, then retry. If it persists, check whether the server supports the selected SSH/SFTP transport.";
        if (text.contains("symlink") || text.contains("symbolic") || text.contains("escaping") || text.contains("unsafe") || text.contains("outside configured") || text.contains("remain inside") || text.contains("stay inside"))
            return details + "\n\nWhat happened: The requested path is outside the allowed folders, is a symbolic link, or has an unexpected file type.\n\nYour options: Check the displayed path and configured folders. Use a regular file or directory inside the intended installation. Preserve the original data before replacing a link or correcting a path; do not bypass the path safety check.";
        if (text.contains("retired"))
            return details + "\n\nWhat happened: This device identity has been retired from synchronization.\n\nYour options: Back up local changes and register a new device identity using the installation setup procedure before synchronizing again. Do not reuse or manually reactivate the retired identity.";
        if (text.contains("json") || text.contains("expected json") || text.contains("records must") || text.contains("not an object"))
            return details + "\n\nWhat happened: The data file does not contain the expected JSON records.\n\nYour options: Back up the named file. Correct its JSON syntax and record structure, or restore a known-good copy, then reopen it. Do not replace it with an empty file if it contains data you need.";
        if (text.contains("duplicate") || text.contains("identity used for different"))
            return details + "\n\nWhat happened: Multiple items share a name, path, or identity that must be unique.\n\nYour options: Inspect the named items and preserve both before making changes. Give independently created items distinct names; if their internal identities collide, re-import one as a new item instead of editing the journal.";
        if (text.contains("selected record") || text.contains("existing conflict revision") || text.contains("historical put revision") || text.contains("unknown device") || text.contains("not registered") || text.contains("could not be registered"))
            return details + "\n\nWhat happened: The selected item or revision is no longer available for this action.\n\nYour options: Refresh or reopen the box, then select an existing item or revision. Check synchronization history before restoring or deleting anything.";
        if (text.contains("setup") || text.contains("profile") || text.contains("installation") || text.contains("ssh port") || text.contains("ssh transport") || text.contains("ssh host") || text.contains("remote path") || text.contains("sync destination") || text.contains("box title") || text.contains("imagewidth") || text.contains("fields has") || text.contains("language must"))
            return details + "\n\nWhat happened: The configuration is incomplete, invalid, or no longer matches the installation.\n\nYour options: Open Setup and correct the setting identified in the details. Check the selected box and installation destination before retrying. Back up setup.ini before editing it manually.";
        if (text.contains("poster") || text.contains("select at least") || text.contains("select between"))
            return details + "\n\nWhat happened: The selected images or layout cannot produce the requested poster.\n\nYour options: Adjust the selection or the size, margins, grid, and text settings identified in the details, then preview the poster again.";
        if (text.contains("metadata") || text.contains("mp4") || text.contains("cannot write") || text.contains("media reader"))
            return details + "\n\nWhat happened: The media metadata could not be read or written in the expected format.\n\nYour options: Keep a backup of the original file. Check that it opens normally and that required media tools are installed. Try a supported media file; if this file alone fails, repair or convert a copy before importing it.";
        if (text.contains("no such file") || text.contains("not found") || text.contains("filenotfound") || text.contains("missing") || text.contains("unavailable") || text.contains("no longer available"))
            return details + "\n\nWhat happened: A required file, folder, or program is unavailable.\n\nYour options: Check the displayed path, reconnect any external drive, and confirm that the file or required program exists and is accessible. Restore missing data from backup before retrying.";
        if (text.contains("quantity") || text.contains("category") || text.contains("filename") || text.contains("single line") || text.contains("regular expression") || text.contains("unsupported") || text.contains("select a") || text.contains("must be"))
            return details + "\n\nWhat happened: The supplied value or selected file is not accepted for this action.\n\nYour options: Correct the value or choose a supported file according to the details, then try again.";
        if (text.contains("exited with status") || text.contains("failed ("))
            return details + "\n\nWhat happened: An external tool stopped with an error.\n\nYour options: Review the tool output and check its installation, input paths, and server access. Correct the reported cause before retrying.";
        return details + "\n\nWhat happened: The requested operation could not complete. The details below are the available diagnostic information.\n\nYour options: Review the details and the current files before retrying; earlier steps may have completed. If the error repeats, save this message and the steps that caused it for troubleshooting. Keep backups and do not delete synchronization history as a workaround.";
    }
}
