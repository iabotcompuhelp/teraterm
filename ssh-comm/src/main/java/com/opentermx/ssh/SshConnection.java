package com.opentermx.ssh;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.opentermx.common.connection.Connection;
import com.opentermx.common.connection.ConnectionConfig;
import com.opentermx.common.connection.ConnectionState;
import com.opentermx.common.connection.DataHandler;
import com.opentermx.common.connection.SshAuth;
import com.opentermx.common.connection.SshConfig;
import com.opentermx.common.connection.StateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class SshConnection implements Connection {

    private static final Logger log = LoggerFactory.getLogger(SshConnection.class);
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int CHANNEL_TIMEOUT_MS = 10_000;
    private static final int READ_BUFFER_SIZE = 8192;
    private static final String DEFAULT_TERM_TYPE = "xterm-256color";

    private final String id;
    private final SshConfig config;
    private final AtomicReference<ConnectionState> state =
            new AtomicReference<>(ConnectionState.DISCONNECTED);

    private JSch jsch;
    private Session session;
    private ChannelShell channel;
    private InputStream remoteIn;
    private OutputStream remoteOut;
    private Thread readerThread;

    private volatile DataHandler dataHandler;
    private volatile StateHandler stateHandler;

    private volatile int ptyCols = 80;
    private volatile int ptyRows = 24;

    public SshConnection(SshConfig config) {
        this.id = UUID.randomUUID().toString();
        this.config = config;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public ConnectionConfig getConfig() {
        return config;
    }

    @Override
    public ConnectionState getState() {
        return state.get();
    }

    @Override
    public void connect() throws Exception {
        transition(ConnectionState.CONNECTING, null);
        try {
            jsch = new JSch();

            File knownHosts = new File(System.getProperty("user.home"), ".ssh/known_hosts");
            if (knownHosts.isFile()) {
                jsch.setKnownHosts(knownHosts.getAbsolutePath());
            }

            SshAuth auth = config.getAuth();
            if (auth instanceof SshAuth.PublicKey k) {
                char[] passphrase = k.getPassphrase();
                byte[] passphraseBytes = (passphrase != null)
                        ? new String(passphrase).getBytes(StandardCharsets.UTF_8)
                        : null;
                jsch.addIdentity(k.getPrivateKeyPath(), passphraseBytes);
            }

            session = jsch.getSession(config.getUsername(), config.getHost(), config.getPort());

            String passwordStr = null;
            char[] passphraseChars = null;
            if (auth instanceof SshAuth.Password p) {
                passwordStr = new String(p.getPassword());
                session.setPassword(passwordStr);
            } else if (auth instanceof SshAuth.PublicKey k) {
                passphraseChars = k.getPassphrase();
            }
            session.setUserInfo(new SimpleUserInfo(passwordStr, passphraseChars, true));

            // Auto-accept unknown host keys (logs fingerprint via UserInfo).
            // TODO: surface a UI prompt with the fingerprint for proper TOFU.
            session.setConfig("StrictHostKeyChecking", "no");
            session.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive");

            session.setServerAliveInterval(Math.max(0, config.getKeepAliveSeconds()) * 1000);

            session.connect(CONNECT_TIMEOUT_MS);
            log.info("SSH session established: {}@{}:{}", config.getUsername(), config.getHost(), config.getPort());

            channel = (ChannelShell) session.openChannel("shell");
            channel.setPtyType(DEFAULT_TERM_TYPE);
            channel.setPtySize(ptyCols, ptyRows, ptyCols * 8, ptyRows * 16);
            remoteIn = channel.getInputStream();
            remoteOut = channel.getOutputStream();
            channel.connect(CHANNEL_TIMEOUT_MS);

            startReader();
            transition(ConnectionState.CONNECTED, null);
        } catch (Exception e) {
            transition(ConnectionState.ERROR, e);
            cleanup();
            throw e;
        }
    }

    private void startReader() {
        readerThread = new Thread(this::readLoop, "ssh-reader-" + config.getHost());
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        byte[] buf = new byte[READ_BUFFER_SIZE];
        try {
            while (remoteIn != null) {
                int n = remoteIn.read(buf);
                if (n < 0) break;
                if (n == 0) continue;
                DataHandler h = dataHandler;
                if (h != null) {
                    try {
                        h.onData(buf, n);
                    } catch (Throwable t) {
                        log.warn("SSH DataHandler threw", t);
                    }
                }
            }
        } catch (Exception e) {
            ConnectionState s = state.get();
            if (s != ConnectionState.DISCONNECTING && s != ConnectionState.DISCONNECTED) {
                log.error("Error en lectura SSH", e);
                transition(ConnectionState.ERROR, e);
            }
        }
    }

    @Override
    public void send(byte[] data, int offset, int length) throws Exception {
        OutputStream out = remoteOut;
        if (out == null) {
            throw new IOException("No conectado");
        }
        out.write(data, offset, length);
        out.flush();
    }

    @Override
    public void send(byte[] data) throws Exception {
        send(data, 0, data.length);
    }

    @Override
    public void disconnect() {
        ConnectionState old = state.getAndSet(ConnectionState.DISCONNECTING);
        if (old == ConnectionState.DISCONNECTED) {
            return;
        }
        notifyState(ConnectionState.DISCONNECTING, old, null);
        Thread t = readerThread;
        if (t != null) t.interrupt();
        cleanup();
        readerThread = null;
        state.set(ConnectionState.DISCONNECTED);
        notifyState(ConnectionState.DISCONNECTED, ConnectionState.DISCONNECTING, null);
    }

    @Override
    public void close() {
        disconnect();
    }

    @Override
    public void setDataHandler(DataHandler handler) {
        this.dataHandler = handler;
    }

    @Override
    public DataHandler getDataHandler() {
        return dataHandler;
    }

    @Override
    public void setStateHandler(StateHandler handler) {
        this.stateHandler = handler;
    }

    @Override
    public StateHandler getStateHandler() {
        return stateHandler;
    }

    public void resizePty(int cols, int rows) {
        ptyCols = cols;
        ptyRows = rows;
        ChannelShell c = channel;
        if (c != null && c.isConnected()) {
            try {
                c.setPtySize(cols, rows, cols * 8, rows * 16);
            } catch (Exception e) {
                log.warn("PTY resize falló", e);
            }
        }
    }

    private void cleanup() {
        try {
            if (channel != null && channel.isConnected()) channel.disconnect();
        } catch (Exception e) {
            log.warn("Error cerrando canal SSH", e);
        }
        try {
            if (session != null && session.isConnected()) session.disconnect();
        } catch (Exception e) {
            log.warn("Error cerrando sesión SSH", e);
        }
        channel = null;
        session = null;
        remoteIn = null;
        remoteOut = null;
    }

    private void transition(ConnectionState newState, Throwable error) {
        ConnectionState old = state.getAndSet(newState);
        notifyState(newState, old, error);
    }

    private void notifyState(ConnectionState current, ConnectionState old, Throwable error) {
        StateHandler h = stateHandler;
        if (h != null) {
            try {
                h.onStateChange(current, error);
            } catch (Throwable t) {
                log.warn("StateHandler threw", t);
            }
        }
    }
}