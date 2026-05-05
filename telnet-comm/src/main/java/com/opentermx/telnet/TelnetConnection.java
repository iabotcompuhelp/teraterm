package com.opentermx.telnet;

import com.opentermx.common.connection.Connection;
import com.opentermx.common.connection.ConnectionConfig;
import com.opentermx.common.connection.ConnectionState;
import com.opentermx.common.connection.DataHandler;
import com.opentermx.common.connection.StateHandler;
import com.opentermx.common.connection.TelnetConfig;
import org.apache.commons.net.telnet.EchoOptionHandler;
import org.apache.commons.net.telnet.SuppressGAOptionHandler;
import org.apache.commons.net.telnet.TelnetClient;
import org.apache.commons.net.telnet.TerminalTypeOptionHandler;
import org.apache.commons.net.telnet.WindowSizeOptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class TelnetConnection implements Connection {

    private static final Logger log = LoggerFactory.getLogger(TelnetConnection.class);
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_BUFFER_SIZE = 8192;
    private static final String DEFAULT_TERM_TYPE = "xterm-256color";

    private final String id;
    private final TelnetConfig config;
    private final AtomicReference<ConnectionState> state =
            new AtomicReference<>(ConnectionState.DISCONNECTED);

    private TelnetClient client;
    private InputStream remoteIn;
    private OutputStream remoteOut;
    private Thread readerThread;

    private volatile DataHandler dataHandler;
    private volatile StateHandler stateHandler;
    private volatile int ptyCols = 80;
    private volatile int ptyRows = 24;

    public TelnetConnection(TelnetConfig config) {
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
            client = new TelnetClient(DEFAULT_TERM_TYPE);
            client.setConnectTimeout(CONNECT_TIMEOUT_MS);

            if (config.getUseTls()) {
                SSLContext ctx = SSLContext.getDefault();
                client.setSocketFactory(ctx.getSocketFactory());
            }

            // Standard option handlers for an interactive shell-like session
            client.addOptionHandler(new TerminalTypeOptionHandler(DEFAULT_TERM_TYPE, false, false, true, false));
            client.addOptionHandler(new EchoOptionHandler(true, false, true, false));
            client.addOptionHandler(new SuppressGAOptionHandler(true, true, true, true));
            client.addOptionHandler(new WindowSizeOptionHandler(ptyCols, ptyRows, false, false, true, false));

            client.connect(config.getHost(), config.getPort());
            log.info("Telnet conectado a {}:{} (TLS={})", config.getHost(), config.getPort(), config.getUseTls());

            remoteIn = client.getInputStream();
            remoteOut = client.getOutputStream();

            startReader();
            transition(ConnectionState.CONNECTED, null);
        } catch (Exception e) {
            transition(ConnectionState.ERROR, e);
            cleanup();
            throw e;
        }
    }

    private void startReader() {
        readerThread = new Thread(this::readLoop, "telnet-reader-" + config.getHost());
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
                        log.warn("Telnet DataHandler threw", t);
                    }
                }
            }
        } catch (Exception e) {
            ConnectionState s = state.get();
            if (s != ConnectionState.DISCONNECTING && s != ConnectionState.DISCONNECTED) {
                log.error("Error en lectura Telnet", e);
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
     * Updates the cached PTY dimensions. Apache Commons Net does not allow
     * dynamic NAWS subnegotiation after registration, so this only affects
     * the next connection.
     */
    public void resizePty(int cols, int rows) {
        ptyCols = cols;
        ptyRows = rows;
    }

    private void cleanup() {
        TelnetClient c = client;
        if (c != null && c.isConnected()) {
            try {
                c.disconnect();
            } catch (Exception e) {
                log.warn("Error cerrando Telnet", e);
            }
        }
        client = null;
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