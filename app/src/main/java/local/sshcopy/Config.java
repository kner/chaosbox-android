package local.sshcopy;

final class Config {
    static final String SETUP_ASSET = "initial/Setup/setup.ini";
    static java.io.File root(android.content.Context context) throws java.io.IOException {
        return InstallationFiles.root(android.os.Environment.getExternalStorageDirectory(),context.getString(R.string.installation_directory));
    }
    static java.io.File setup(android.content.Context context) throws java.io.IOException {
        return InstallationFiles.owned(root(context),"setup.ini");
    }
    static final String HOST = "access983197478.webspace-data.io";
    static final int PORT = 22;
    static final String USER = "u114229695";
    // Credentials are copied only into the installation-local .state/credentials directory.
    static final String KEY_FILE = "android_copy";
    static final String KNOWN_HOSTS_FILE = "known_hosts";
    private Config() {}
}
