package com.opentermx.ssh;

import com.jcraft.jsch.AgentIdentityRepository;
import com.jcraft.jsch.AgentProxyException;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Proxy;
import com.jcraft.jsch.ProxyHTTP;
import com.jcraft.jsch.ProxySOCKS4;
import com.jcraft.jsch.ProxySOCKS5;
import com.jcraft.jsch.SSHAgentConnector;
import com.jcraft.jsch.Session;
import com.opentermx.common.connection.Connection;
import com.opentermx.common.connection.ConnectionConfig;
import com.opentermx.common.connection.ConnectionState;
import com.opentermx.common.connection.DataHandler;
import com.opentermx.common.connection.HostKeyVerifier;
import com.opentermx.common.connection.PortForward;
import com.opentermx.common.connection.ProxyConfig;
import com.opentermx.common.connection.RejectAllHostKeyVerifier;
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
import java.util.ArrayList;
import java.util.List;
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
    private final HostKeyVerifier hostKeyVerifier;
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
        this(config, RejectAllHostKeyVerifier.INSTANCE);
    }

    public SshConnection(SshConfig config, HostKeyVerifier hostKeyVerifier) {
        this.id = UUID.randomUUID().toString();
        this.config = config;
        this.hostKeyVerifier = hostKeyVerifier != null ? hostKeyVerifier : RejectAllHostKeyVerifier.INSTANCE;
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

            File knownHostsFile = new File(System.getProperty("user.home"), ".ssh/known_hosts");
            File parent = knownHostsFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                log.warn("No se pudo crear el directorio {}", parent);
            }
            if (!knownHostsFile.exists()) {
                try {
                    if (!knownHostsFile.createNewFile()) {
                        log.warn("No se pudo crear {}", knownHostsFile);
                    }
                } catch (IOException e) {
                    log.warn("No se pudo crear {}", knownHostsFile, e);
                }
            }
            if (knownHostsFile.isFile()) {
                jsch.setKnownHosts(knownHostsFile.getAbsolutePath());
            }

            HostKeyRepository defaultRepo = jsch.getHostKeyRepository();
            jsch.setHostKeyRepository(new TofuHostKeyRepository(defaultRepo, hostKeyVerifier));

            // Wire the local SSH agent BEFORE adding any explicit identity, so the
            // agent's keys are tried first either when the user enabled agent forwarding
            // (which forwards keys downstream) or when "try agent first" is on
            // (auth-only). Failures are non-fatal: the agent may simply not be running.
            boolean useAgentAuth = config.getTryAgentFirst() || config.getAgentForwarding();
            boolean agentAvailable = false;
            if (useAgentAuth) {
                try {
                    SSHAgentConnector connector = new SSHAgentConnector();
                    if (connector.isAvailable()) {
                        jsch.setIdentityRepository(new AgentIdentityRepository(connector));
                        agentAvailable = true;
                        log.info("SSH agent disponible — usando como IdentityRepository");
                    } else if (config.getAgentForwarding()) {
                        log.warn("SSH agent forwarding solicitado pero el agente no está disponible");
                    }
                } catch (AgentProxyException e) {
                    log.warn("No se pudo conectar al SSH agent: {}", e.getMessage());
                }
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

            Proxy proxy = buildProxy(config.getProxy());
            if (proxy != null) {
                session.setProxy(proxy);
                log.info("SSH conectando vía proxy {}:{}",
                        config.getProxy().getHost(), config.getProxy().getPort());
            }

            String passwordStr = null;
            char[] passphraseChars = null;
            if (auth instanceof SshAuth.Password p) {
                passwordStr = new String(p.getPassword());
                session.setPassword(passwordStr);
            } else if (auth instanceof SshAuth.PublicKey k) {
                passphraseChars = k.getPassphrase();
            }
            session.setUserInfo(new SimpleUserInfo(passwordStr, passphraseChars));

            // TOFU: TofuHostKeyRepository.check() owns the accept/reject decision.
            // StrictHostKeyChecking=yes makes JSch abort if check() returns anything but OK,
            // without falling back to UserInfo.promptYesNo.
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive");

            applyAlgorithmPreferences(session);

            session.setServerAliveInterval(Math.max(0, config.getKeepAliveSeconds()) * 1000);

            session.connect(CONNECT_TIMEOUT_MS);
            log.info("SSH session established: {}@{}:{}", config.getUsername(), config.getHost(), config.getPort());

            applyInitialPortForwards();

            channel = (ChannelShell) session.openChannel("shell");
            String termType = config.getTerminalType();
            if (termType == null || termType.isBlank()) termType = DEFAULT_TERM_TYPE;
            channel.setPtyType(termType);
            channel.setPtySize(ptyCols, ptyRows, ptyCols * 8, ptyRows * 16);
            if (config.getAgentForwarding() && agentAvailable) {
                channel.setAgentForwarding(true);
            }
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

    private static Proxy buildProxy(ProxyConfig pc) {
        if (pc == null || !pc.isEnabled()) return null;
        switch (pc.getType()) {
            case HTTP -> {
                ProxyHTTP p = new ProxyHTTP(pc.getHost(), pc.getPort());
                if (!pc.getUsername().isEmpty()) {
                    p.setUserPasswd(pc.getUsername(), pc.getPassword());
                }
                return p;
            }
            case SOCKS4 -> {
                ProxySOCKS4 p = new ProxySOCKS4(pc.getHost(), pc.getPort());
                if (!pc.getUsername().isEmpty()) {
                    p.setUserPasswd(pc.getUsername(), pc.getPassword());
                }
                return p;
            }
            case SOCKS5 -> {
                ProxySOCKS5 p = new ProxySOCKS5(pc.getHost(), pc.getPort());
                if (!pc.getUsername().isEmpty()) {
                    p.setUserPasswd(pc.getUsername(), pc.getPassword());
                }
                return p;
            }
            default -> { return null; }
        }
    }

    /**
     * Applies user-configured cipher/kex/MAC preferences and compression to the JSch session
     * before it negotiates. Empty lists mean "leave the default" so we don't accidentally
     * shrink the algorithm set when the user hasn't customised anything.
     */
    private void applyAlgorithmPreferences(Session s) {
        if (config.getCompression()) {
            s.setConfig("compression.s2c", "zlib@openssh.com,zlib,none");
            s.setConfig("compression.c2s", "zlib@openssh.com,zlib,none");
            s.setConfig("compression_level", "6");
        } else {
            s.setConfig("compression.s2c", "none");
            s.setConfig("compression.c2s", "none");
        }
        List<String> ciphers = config.getCiphers();
        if (ciphers != null && !ciphers.isEmpty()) {
            String list = String.join(",", ciphers);
            s.setConfig("cipher.s2c", list);
            s.setConfig("cipher.c2s", list);
        }
        List<String> kex = config.getKex();
        if (kex != null && !kex.isEmpty()) {
            s.setConfig("kex", String.join(",", kex));
        }
        List<String> macs = config.getMacs();
        if (macs != null && !macs.isEmpty()) {
            String list = String.join(",", macs);
            s.setConfig("mac.s2c", list);
            s.setConfig("mac.c2s", list);
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

    /**
     * Registers a TCP port forward on the live session. Returns the actual
     * bound port (may differ from {@code rule.bindPort} when the user passed 0
     * to request dynamic allocation; only meaningful for LOCAL forwards).
     * Throws if the session is not connected.
     */
    private void applyInitialPortForwards() {
        for (PortForward rule : config.getPortForwards()) {
            try {
                addPortForward(rule);
                log.info("Port forward inicial: {}", rule.describe());
            } catch (JSchException e) {
                // Don't abort the whole session over a single forward — just log.
                log.warn("No se pudo registrar el port forward inicial {}: {}", rule.describe(), e.getMessage());
            }
        }
    }

    public int addPortForward(PortForward rule) throws JSchException {
        Session s = requireConnectedSession();
        String bind = rule.getBindAddress();
        if (rule.getDirection() == PortForward.Direction.LOCAL) {
            String addr = bind.isBlank() ? "127.0.0.1" : bind;
            return s.setPortForwardingL(addr, rule.getBindPort(), rule.getTargetHost(), rule.getTargetPort());
        } else {
            String addr = bind.isBlank() ? null : bind;
            s.setPortForwardingR(addr, rule.getBindPort(), rule.getTargetHost(), rule.getTargetPort());
            return rule.getBindPort();
        }
    }

    public void removePortForward(PortForward rule) throws JSchException {
        Session s = requireConnectedSession();
        String bind = rule.getBindAddress();
        if (rule.getDirection() == PortForward.Direction.LOCAL) {
            String addr = bind.isBlank() ? "127.0.0.1" : bind;
            s.delPortForwardingL(addr, rule.getBindPort());
        } else {
            String addr = bind.isBlank() ? null : bind;
            s.delPortForwardingR(addr, rule.getBindPort());
        }
    }

    public List<PortForward> listPortForwards() throws JSchException {
        Session s = requireConnectedSession();
        List<PortForward> out = new ArrayList<>();
        for (String spec : s.getPortForwardingL()) {
            PortForward pf = parseLocalSpec(spec);
            if (pf != null) out.add(pf);
        }
        for (String spec : s.getPortForwardingR()) {
            PortForward pf = parseRemoteSpec(spec);
            if (pf != null) out.add(pf);
        }
        return out;
    }

    private Session requireConnectedSession() throws JSchException {
        Session s = session;
        if (s == null || !s.isConnected()) {
            throw new JSchException("SSH session no está conectada");
        }
        return s;
    }

    // jsch returns local forwards as either "lport:host:rport" or "bind:lport:host:rport".
    // Remote forwards have the same shape via ChannelForwardedTCPIP.getPortForwarding.
    private static PortForward parseLocalSpec(String spec) {
        return parseSpec(spec, PortForward.Direction.LOCAL);
    }

    private static PortForward parseRemoteSpec(String spec) {
        return parseSpec(spec, PortForward.Direction.REMOTE);
    }

    private static PortForward parseSpec(String spec, PortForward.Direction dir) {
        if (spec == null || spec.isBlank()) return null;
        String[] parts = spec.split(":");
        try {
            if (parts.length == 3) {
                return new PortForward(dir, "", Integer.parseInt(parts[0]), parts[1], Integer.parseInt(parts[2]));
            }
            if (parts.length == 4) {
                return new PortForward(dir, parts[0], Integer.parseInt(parts[1]), parts[2], Integer.parseInt(parts[3]));
            }
        } catch (NumberFormatException ignored) {
        }
        log.warn("No pude parsear forward '{}'", spec);
        return null;
    }

    /**
     * Opens an SFTP channel multiplexed over the existing SSH session. The
     * caller owns the returned client and must close it. Throws if the SSH
     * session is not currently connected.
     */
    public SftpClient openSftp() throws JSchException {
        Session s = requireConnectedSession();
        ChannelSftp sftp = (ChannelSftp) s.openChannel("sftp");
        sftp.connect(CHANNEL_TIMEOUT_MS);
        return new SftpClient(sftp);
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