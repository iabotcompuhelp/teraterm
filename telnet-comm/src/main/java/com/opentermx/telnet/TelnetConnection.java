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
import org.apache.commons.net.telnet.TelnetOptionHandler;
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
    private final String terminalType;
    private final AtomicReference<ConnectionState> state =
            new AtomicReference<>(ConnectionState.DISCONNECTED);

    private TelnetClient client;
    // Volátiles: los escribe el thread que conecta/desconecta y los lee el reader
    // thread (remoteIn) y cualquier thread que envíe (remoteOut).
    private volatile InputStream remoteIn;
    private volatile OutputStream remoteOut;
    private Thread readerThread;

    private volatile DataHandler dataHandler;
    private volatile StateHandler stateHandler;
    private volatile int ptyCols = 80;
    private volatile int ptyRows = 24;

    public TelnetConnection(TelnetConfig config) {
        this.id = UUID.randomUUID().toString();
        this.config = config;
        String t = config.getTerminalType();
        this.terminalType = (t == null || t.isBlank()) ? DEFAULT_TERM_TYPE : t;
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
        // Reconexión: si quedó vivo un client de un connect() anterior, liberarlo
        // antes de pisarlo — si no, el socket viejo queda conectado para siempre.
        if (client != null || readerThread != null) {
            log.warn("connect() con client Telnet previo — liberando antes de reconectar");
            // DISCONNECTING sin notificar: el reader viejo va a morir con una excepción
            // de socket cerrado y no debe reportarla como ERROR de la conexión nueva.
            state.set(ConnectionState.DISCONNECTING);
            teardown();
        }
        transition(ConnectionState.CONNECTING, null);
        try {
            client = new TelnetClient(terminalType);
            client.setConnectTimeout(CONNECT_TIMEOUT_MS);

            // Phase 2.5 T3: diagnóstico de la negociación IAC. La app setea esta system
            // property desde `AppSettings.additional.telnetVerboseLog` (toggle en Setup →
            // Additional → Log). El spy stream loguea WILL/WONT/DO/DONT en ambas
            // direcciones a stderr — útil para verificar si el server quiere hacer echo
            // pero OpenTermX lo rechaza (síntoma: TCP conecta pero no aparece prompt).
            if ("true".equalsIgnoreCase(System.getProperty("opentermx.telnet.verboseLog"))) {
                client.registerSpyStream(System.err);
                log.info("Telnet verbose log activo — la negociación IAC se loguea en stderr");
            }

            if (config.getUseTls()) {
                SSLContext ctx = SSLContext.getDefault();
                client.setSocketFactory(ctx.getSocketFactory());
            }

            // Settings/Setup → Proxy and Setup → TCP/IP feed these. setProxy must run
            // before connect() so the underlying socket is created via the proxy.
            java.net.Proxy proxy = JavaNetProxies.create(config.getProxy());
            if (proxy != null) {
                client.setProxy(proxy);
                log.info("Telnet vía proxy {}:{}", config.getProxy().getHost(), config.getProxy().getPort());
            }
            int recvBuf = config.getRecvBufferSize();
            // setReceiveBufferSize en SocketClient guarda el valor y lo aplica en connect();
            // setKeepAlive, en cambio, hace `_socket_.setKeepAlive(...)` directo y tira NPE
            // si se llama antes de connect(). Por eso recvBuf va acá y keepAlive abajo.
            if (recvBuf > 0) client.setReceiveBufferSize(recvBuf);

            for (TelnetOptionHandler h : buildOptionHandlers(terminalType, ptyCols, ptyRows)) {
                client.addOptionHandler(h);
            }

            java.net.InetAddress address = HostResolver.resolve(config.getHost(), config.getDnsMode());
            client.connect(address, config.getPort());
            client.setKeepAlive(config.getKeepAlive());
            log.info("Telnet conectado a {}:{} (host={}, TLS={})",
                    address.getHostAddress(), config.getPort(), config.getHost(), config.getUseTls());

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

    /**
     * Construye los option handlers que registra una sesión interactiva contra CLI de
     * equipos de red. Extraído como package-private static para que el test pueda
     * verificar la configuración sin necesitar un servidor Telnet real.
     *
     * <p>Phase 2.5 T3 invirtió el {@link EchoOptionHandler} de {@code (true,false,true,false)}
     * a {@code (false,true,false,true)} tras observar doble eco contra 3Com Baseline 2928
     * y equivalentes — el server siempre hace eco (necesario para password masking),
     * así que el cliente debe pedirlo explícitamente y dejarlo manejar el echo.
     */
    static TelnetOptionHandler[] buildOptionHandlers(String terminalType, int ptyCols, int ptyRows) {
        return new TelnetOptionHandler[] {
            new TerminalTypeOptionHandler(terminalType, false, false, true, false),
            new EchoOptionHandler(false, true, false, true),
            new SuppressGAOptionHandler(true, true, true, true),
            new WindowSizeOptionHandler(ptyCols, ptyRows, false, false, true, false),
        };
    }

    private void startReader() {
        readerThread = new Thread(this::readLoop, "telnet-reader-" + config.getHost());
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        byte[] buf = new byte[READ_BUFFER_SIZE];
        try {
            InputStream in;
            while (!Thread.currentThread().isInterrupted() && (in = remoteIn) != null) {
                int n = in.read(buf);
                if (n < 0) break;
                if (n == 0) continue;
                DataHandler h = dataHandler;
                if (h != null) {
                    try {
                        // Copia defensiva: buf se reusa en la próxima iteración y el
                        // contrato de DataHandler promete un array que es del receptor.
                        h.onData(java.util.Arrays.copyOf(buf, n), n);
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
        teardown();
        state.set(ConnectionState.DISCONNECTED);
        notifyState(ConnectionState.DISCONNECTED, ConnectionState.DISCONNECTING, null);
    }

    /**
     * Cierra el client y espera a que el reader thread termine. El orden importa:
     * un read() bloqueado en I/O no responde a interrupt() — sólo al cierre del
     * socket subyacente — así que cleanup() va primero y el interrupt cubre los
     * estados no bloqueados. El join con timeout hace la terminación observable.
     */
    private void teardown() {
        Thread t = readerThread;
        cleanup();
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try {
                t.join(2_000);
                if (t.isAlive()) log.warn("El reader Telnet no terminó tras 2s");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        readerThread = null;
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