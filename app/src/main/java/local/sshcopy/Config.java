package local.sshcopy;

final class Config {
    // Relative source paths resolve under shared internal storage.
    static final String SOURCE = "ChaosBox/JPG";
    static final String SETUP = "ChaosBox/Setup/setup.ini";
    static final String SETUP_ASSET = "initial/Setup/setup.ini";
    static final String BOXES = "ChaosBox/boxes";
    static final String HOST = "access983197478.webspace-data.io";
    static final int PORT = 22;
    static final String USER = "u114229695";
    // Relative destination paths resolve under the SSH user's home directory.
    static final String IMAGE_DESTINATION = "l1/storage/app/exif/jpg";
    static final String JSON_DESTINATION = "l1/storage/app/exif/data";
    // Files in the app's private files directory (or optional bundled assets).
    static final String KEY_FILE = "android_copy";
    static final String KNOWN_HOSTS_FILE = "known_hosts";
    private Config() {}
}
