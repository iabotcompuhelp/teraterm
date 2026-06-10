package com.opentermx.serial;

import com.opentermx.common.connection.ConnectionConfig;
import com.opentermx.common.connection.ConnectionState;
import com.opentermx.common.connection.DataHandler;
import com.opentermx.common.connection.SerialConfig;
import com.opentermx.common.connection.StateHandler;
import com.opentermx.serial.nativeio.NativeSerialConfig;
import com.opentermx.serial.nativeio.NativeSerialPort;
import com.opentermx.serial.nativeio.OpenTermXNative;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Backend de puerto serial basado en la librería nativa {@code opentermx_native}
 * (ver native/src/serial_native.c y serial-comm/.../nativeio/).
 *
 * <p>Drop-in para {@link SerialConnection}: misma interfaz {@link SerialPortConnection},
 * mismo modelo de estado, mismo reader-loop con history circular.</p>
 */
public final class NativeSerialConnection implements SerialPortConnection {

    private static final Logger log = LoggerFactory.getLogger(NativeSerialConnection.class);
    private static final int DEFAULT_HISTORY_BYTES = 64 * 1024;
    private static final int READ_BUFFER_SIZE = 4096;
    private static final int READ_TIMEOUT_MS = 100;
    private static final int WRITE_TIMEOUT_MS = 1000;

    private final String id;
    private final SerialConfig config;
    private final AtomicReference<ConnectionState> state =
            new AtomicReference<>(ConnectionState.DISCONNECTED);
    private final CircularByteBuffer history;

    private volatile NativeSerialPort port;
    private Thread readerThread;
    private volatile DataHandler dataHandler;
    private volatile StateHandler stateHandler;
    private volatile boolean dtr = true;
    private volatile boolean rts = true;

    public NativeSerialConnection(SerialConfig config) {
        this(config, DEFAULT_HISTORY_BYTES);
    }

    public NativeSerialConnection(SerialConfig config, int historyBytes) {
        this.id = UUID.randomUUID().toString();
        this.config = config;
        this.history = new CircularByteBuffer(historyBytes);
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
        // Reconexión: si quedó abierto un puerto de un connect() anterior, liberarlo
        // antes de pisarlo — si no, el handle nativo viejo queda ocupando el COM port.
        if (port != null || readerThread != null) {
            log.warn("connect() con puerto serial nativo previo — liberando antes de reconectar");
            // DISCONNECTING sin notificar: el reader viejo va a morir con una excepción
            // de puerto cerrado y no debe reportarla como ERROR de la conexión nueva.
            state.set(ConnectionState.DISCONNECTING);
            teardown();
        }
        transition(ConnectionState.CONNECTING, null);
        try {
            NativeSerialConfig cfg = toNative(config);
            port = NativeSerialPort.open(config.getPortName(), cfg);
            applySignals();
            startReader();
            transition(ConnectionState.CONNECTED, null);
        } catch (Exception e) {
            transition(ConnectionState.ERROR, e);
            cleanupPort();
            throw e;
        }
    }

    private void applySignals() {
        NativeSerialPort p = port;
        if (p == null) return;
        try {
            p.setDTR(dtr);
            p.setRTS(rts);
        } catch (IOException e) {
            log.warn("No se pudo aplicar DTR/RTS iniciales", e);
        }
    }

    private void startReader() {
        readerThread = new Thread(this::readLoop, "native-serial-reader-" + config.getPortName());
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        byte[] buf = new byte[READ_BUFFER_SIZE];
        try {
            while (!Thread.currentThread().isInterrupted() && port != null) {
                int n;
                try {
                    n = port.read(buf);
                } catch (IOException e) {
                    if (Thread.currentThread().isInterrupted() || port == null) break;
                    throw e;
                }
                if (n > 0) {
                    history.write(buf, 0, n);
                    DataHandler h = dataHandler;
                    if (h != null) {
                        try {
                            // Copia defensiva: buf se reusa en la próxima iteración y el
                            // contrato de DataHandler promete un array que es del receptor.
                            h.onData(java.util.Arrays.copyOf(buf, n), n);
                        } catch (Throwable t) {
                            log.warn("DataHandler threw", t);
                        }
                    }
                }
            }
        } catch (Exception e) {
            ConnectionState s = state.get();
            if (s != ConnectionState.DISCONNECTING && s != ConnectionState.DISCONNECTED) {
                log.error("Error en lectura serial nativa", e);
                transition(ConnectionState.ERROR, e);
            }
        }
    }

    @Override
    public void send(byte[] data, int offset, int length) throws Exception {
        NativeSerialPort p = port;
        if (p == null) {
            throw new IOException("No conectado");
        }
        byte[] toWrite;
        if (offset == 0 && length == data.length) {
            toWrite = data;
        } else {
            toWrite = new byte[length];
            System.arraycopy(data, offset, toWrite, 0, length);
        }
        int written = p.write(toWrite, length);
        if (written != length) {
            throw new IOException("Escritura serial parcial: " + written + "/" + length);
        }
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
     * Cierra el puerto y espera a que el reader thread termine (el read nativo con
     * timeout de 100ms garantiza que vea el interrupt enseguida). El join con timeout
     * hace la terminación observable.
     */
    private void teardown() {
        Thread t = readerThread;
        cleanupPort();
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try {
                t.join(2_000);
                if (t.isAlive()) log.warn("El reader serial nativo no terminó tras 2s");
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

    @Override
    public void sendBreak(int durationMillis) {
        NativeSerialPort p = port;
        if (p == null) return;
        try {
            p.sendBreak(durationMillis);
        } catch (IOException e) {
            log.warn("sendBreak falló", e);
        }
    }

    @Override
    public void setDTR(boolean on) {
        dtr = on;
        NativeSerialPort p = port;
        if (p == null) return;
        try {
            p.setDTR(on);
        } catch (IOException e) {
            log.warn("setDTR falló", e);
        }
    }

    @Override
    public void setRTS(boolean on) {
        rts = on;
        NativeSerialPort p = port;
        if (p == null) return;
        try {
            p.setRTS(on);
        } catch (IOException e) {
            log.warn("setRTS falló", e);
        }
    }

    @Override
    public SerialSignals readSignals() {
        NativeSerialPort p = port;
        if (p == null) {
            return new SerialSignals(false, false, false, false, false, false);
        }
        try {
            int mask = p.readSignals();
            return new SerialSignals(
                    (mask & OpenTermXNative.SIGNAL_DTR) != 0,
                    (mask & OpenTermXNative.SIGNAL_RTS) != 0,
                    (mask & OpenTermXNative.SIGNAL_CTS) != 0,
                    (mask & OpenTermXNative.SIGNAL_DSR) != 0,
                    (mask & OpenTermXNative.SIGNAL_DCD) != 0,
                    (mask & OpenTermXNative.SIGNAL_RI)  != 0);
        } catch (IOException e) {
            log.warn("readSignals falló", e);
            return new SerialSignals(dtr, rts, false, false, false, false);
        }
    }

    @Override
    public CircularByteBuffer history() {
        return history;
    }

    private void cleanupPort() {
        NativeSerialPort p = port;
        if (p != null) {
            try {
                p.close();
            } catch (Exception e) {
                log.warn("Error cerrando puerto serial nativo", e);
            }
        }
        port = null;
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

    private static NativeSerialConfig toNative(SerialConfig src) {
        NativeSerialConfig out = new NativeSerialConfig();
        out.baudRate = src.getBaudRate();
        out.dataBits = src.getDataBits();
        out.stopBits = toNativeStopBits(src.getStopBits());
        out.parity = toNativeParity(src.getParity());
        out.flowControl = toNativeFlow(src.getFlowControl());
        out.readTimeoutMs = READ_TIMEOUT_MS;
        out.writeTimeoutMs = WRITE_TIMEOUT_MS;
        return out;
    }

    private static int toNativeStopBits(SerialConfig.StopBits sb) {
        return switch (sb) {
            case ONE -> NativeSerialConfig.STOP_ONE;
            case ONE_AND_HALF -> NativeSerialConfig.STOP_ONE_HALF;
            case TWO -> NativeSerialConfig.STOP_TWO;
        };
    }

    private static int toNativeParity(SerialConfig.Parity p) {
        return switch (p) {
            case NONE -> NativeSerialConfig.PARITY_NONE;
            case ODD -> NativeSerialConfig.PARITY_ODD;
            case EVEN -> NativeSerialConfig.PARITY_EVEN;
            case MARK -> NativeSerialConfig.PARITY_MARK;
            case SPACE -> NativeSerialConfig.PARITY_SPACE;
        };
    }

    private static int toNativeFlow(SerialConfig.FlowControl fc) {
        return switch (fc) {
            case NONE -> NativeSerialConfig.FLOW_NONE;
            case RTS_CTS -> NativeSerialConfig.FLOW_RTSCTS;
            case XON_XOFF -> NativeSerialConfig.FLOW_XONXOFF;
        };
    }
}