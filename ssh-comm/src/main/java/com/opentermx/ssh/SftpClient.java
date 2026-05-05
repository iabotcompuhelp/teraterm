package com.opentermx.ssh;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Vector;

/**
 * Thin façade over jsch's {@link ChannelSftp} that exposes only the operations
 * the OpenTermX UI needs. Instances are tied to a single channel and must be
 * closed when no longer used.
 */
public final class SftpClient implements AutoCloseable {

    private final ChannelSftp channel;

    SftpClient(ChannelSftp channel) {
        this.channel = channel;
    }

    public String pwd() throws SftpException {
        return channel.pwd();
    }

    public void cd(String remoteDir) throws SftpException {
        channel.cd(remoteDir);
    }

    public List<SftpEntry> list(String remoteDir) throws SftpException {
        @SuppressWarnings("unchecked")
        Vector<ChannelSftp.LsEntry> raw = channel.ls(remoteDir);
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<SftpEntry> out = new ArrayList<>(raw.size());
        for (ChannelSftp.LsEntry e : raw) {
            SftpATTRS a = e.getAttrs();
            out.add(new SftpEntry(
                    e.getFilename(),
                    a.isDir(),
                    a.isLink(),
                    a.getSize(),
                    a.getMTime() * 1000L,
                    a.getPermissionsString()
            ));
        }
        return out;
    }

    public void download(String remotePath, OutputStream localOut, ProgressListener listener) throws SftpException {
        channel.get(remotePath, localOut, adapt(listener));
    }

    public void upload(InputStream localIn, String remotePath, ProgressListener listener) throws SftpException {
        channel.put(localIn, remotePath, adapt(listener), ChannelSftp.OVERWRITE);
    }

    public void mkdir(String remotePath) throws SftpException {
        channel.mkdir(remotePath);
    }

    public void rmdir(String remotePath) throws SftpException {
        channel.rmdir(remotePath);
    }

    public void rm(String remotePath) throws SftpException {
        channel.rm(remotePath);
    }

    public void rename(String from, String to) throws SftpException {
        channel.rename(from, to);
    }

    public SftpEntry stat(String remotePath) throws SftpException {
        SftpATTRS a = channel.lstat(remotePath);
        String name = remotePath.substring(remotePath.lastIndexOf('/') + 1);
        return new SftpEntry(
                name,
                a.isDir(),
                a.isLink(),
                a.getSize(),
                a.getMTime() * 1000L,
                a.getPermissionsString()
        );
    }

    public boolean isConnected() {
        return channel.isConnected();
    }

    @Override
    public void close() {
        if (channel.isConnected()) channel.disconnect();
    }

    public interface ProgressListener {
        void onProgress(long transferred, long total);

        void onComplete(boolean success);

        ProgressListener NOOP = new ProgressListener() {
            @Override public void onProgress(long transferred, long total) {}
            @Override public void onComplete(boolean success) {}
        };
    }

    private static SftpProgressMonitor adapt(ProgressListener listener) {
        ProgressListener l = (listener != null) ? listener : ProgressListener.NOOP;
        return new SftpProgressMonitor() {
            private long total = 0;
            private long transferred = 0;

            @Override
            public void init(int op, String src, String dest, long max) {
                total = max;
                transferred = 0;
                l.onProgress(0, total);
            }

            @Override
            public boolean count(long count) {
                transferred += count;
                l.onProgress(transferred, total);
                return true;
            }

            @Override
            public void end() {
                l.onComplete(true);
            }
        };
    }
}