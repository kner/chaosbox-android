package local.sshcopy;
import java.io.File;
import com.jcraft.jsch.*;
public final class SftpSyncTest {
    public static void main(String[] args) throws Exception {
        File root=new File(args[0]);SyncStore store=new SyncStore(new File(root,"JPG"),new File(root,"TXT"),new SyncStore.Metadata(){
            public org.json.JSONObject read(File file) throws Exception {return new org.json.JSONObject(LocalData.read(file));}
            public void write(File file,org.json.JSONObject record) throws Exception {SyncStore.atomic(file,SyncStore.canonical(record).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        });
        JSch ssh=new JSch();ssh.setKnownHosts(args[3]);ssh.addIdentity(args[2]);
        Session session=ssh.getSession("tester","127.0.0.1",Integer.parseInt(args[1]));
        session.setConfig("StrictHostKeyChecking","yes");session.setConfig("PreferredAuthentications","publickey");
        session.connect(10000);
        try {ChannelSftp sftp=(ChannelSftp)session.openChannel("sftp");sftp.connect(10000);
            try(AutoCloseable lock=store.lock()){SyncSftp.exchange(store,sftp,"shared/TXT/.chaosbox-sync",()->{},message->{});System.out.println(store.apply().size());}
            finally{sftp.disconnect();}
        } finally{session.disconnect();}
    }
}
