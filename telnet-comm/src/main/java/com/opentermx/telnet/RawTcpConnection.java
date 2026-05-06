package com.opentermx.telnet;

import com.opentermx.common.connection.Connection;
import com.opentermx.common.connection.ConnectionConfig;
import com.opentermx.common.connection.ConnectionState;
import com.opentermx.common.connection.DataHandler;
import com.opentermx.common.connection.StateHandler;
import com.opentermx.common.connection.TcpRawConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plain-TCP connection with no protocol negotiation. Sends and receives raw
 * bytes against an arbitrary host:port endpoint. Useful for embedded devices,
 * netcat-style probing, MUDs, custom binary protocols, etc.
 */
public final class RawTcpConnection implements Connection {

    private static final Logger log = LoggerFactory.getLogger(RawTcpConnection.class);
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_BUFFER_SIZE = 8192;

    private final String id;
    private final TcpRawConfig config;
    private final AtomicReference<ConnectionState> state =
            new AtomicReference<>(ConnectionState.DISCONNECTED);

    private Socket socket;
    private InputStream remoteIn;
    private OutputStream remoteOut;
    private Thread readerThread;

    private volatile DataHandler dataHandler;
    private volatile StateHandler stateHandler;

    public RawTcpConnection(TcpRawConfig config) {
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
            java.net.Proxy proxy = JavaNetProxies.create(config.getProxy());
            socket = (proxy != null) ? new Socket(proxy) : new Socket();
            int recvBuf = config.getRecvBufferSize();
            // setReceiveBufferSize must precede connect() to influence the TCP receive window.
            if (recvBuf > 0) socket.setReceiveBufferSize(recvBuf);
            java.net.InetAddress address = HostResolver.resolve(config.getHost(), config.getDnsMode());
            socket.connect(new InetSocketAddress(address, config.getPort()), CONNECT_TIMEOUT_MS);
            socket.setKeepAlive(config.getKeepAlive());
            socket.setTcpNoDelay(true);
            remoteIn = socket.getInputStream();
            remoteOut = socket.getOutputStream();
            log.info("TCP raw conectado a {}:{}{}",
                    config.getHost(), config.getPort(),
                    proxy != null ? " (vía proxy " + config.getProxy().getHost() + ")" : "");
            startReader();
            transition(ConnectionState.CONNECTED, null);
        } catch (Exception e) {
            transition(ConnectionState.ERROR, e);
            cleanup();
            throw e;
        }
    }

    private void startReader() {
        readerThread = new Thread(this::readLoop, "rawtcp-reader-" + config.getHost());
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
                        log.warn("RawTcp DataHandler threw", t);
                    }
                }
            }
        } catch (Exception e) {
            ConnectionState s = state.get();
            if (s != ConnectionState.DISCONNECTING && s != ConnectionState.DISCONNECTED) {
                log.error("Error en lectura TCP raw", e);
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

    private void cleanup() {
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                log.warn("Error cerrando socket TCP raw", e);
            }
        }
        socket = null;
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