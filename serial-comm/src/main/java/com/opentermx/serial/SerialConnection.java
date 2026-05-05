package com.opentermx.serial;

import com.fazecast.jSerialComm.SerialPort;
import com.opentermx.common.connection.Connection;
import com.opentermx.common.connection.ConnectionConfig;
import com.opentermx.common.connection.ConnectionState;
import com.opentermx.common.connection.DataHandler;
import com.opentermx.common.connection.SerialConfig;
import com.opentermx.common.connection.StateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class SerialConnection implements Connection {

    private static final Logger log = LoggerFactory.getLogger(SerialConnection.class);
    private static final int DEFAULT_HISTORY_BYTES = 64 * 1024;
    private static final int READ_BUFFER_SIZE = 4096;
    private static final int READ_TIMEOUT_MS = 100;

    private final String id;
    private final SerialConfig config;
    private final AtomicReference<ConnectionState> state =
            new AtomicReference<>(ConnectionState.DISCONNECTED);
    private final CircularByteBuffer history;

    private SerialPort port;
    private Thread readerThread;
    private volatile DataHandler dataHandler;
    private volatile StateHandler stateHandler;
    private volatile boolean dtr = true;
    private volatile boolean rts = true;

    public SerialConnection(SerialConfig config) {
        this(config, DEFAULT_HISTORY_BYTES);
    }

    public SerialConnection(SerialConfig config, int historyBytes) {
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
        transition(ConnectionState.CONNECTING, null);
        try {
            port = SerialPort.getCommPort(config.getPortName());
            port.setBaudRate(config.getBaudRate());
            port.setNumDataBits(config.getDataBits());
            port.setNumStopBits(toJSerialStopBits(config.getStopBits()));
            port.setParity(toJSerialParity(config.getParity()));
            port.setFlowControl(toJSerialFlow(config.getFlowControl()));
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, READ_TIMEOUT_MS, 0);
            if (!port.openPort()) {
                throw new IOException("No se pudo abrir el puerto " + config.getPortName());
            }
            port.setDTR();
            port.setRTS();
            startReader();
            transition(ConnectionState.CONNECTED, null);
        } catch (Exception e) {
            transition(ConnectionState.ERROR, e);
            cleanupPort();
            throw e;
        }
    }

    private void startReader() {
        readerThread = new Thread(this::readLoop, "serial-reader-" + config.getPortName());
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop() {
        byte[] buf = new byte[READ_BUFFER_SIZE];
        try {
            while (!Thread.currentThread().isInterrupted() && port != null && port.isOpen()) {
                int n = port.readBytes(buf, buf.length);
                if (n > 0) {
                    history.write(buf, 0, n);
                    DataHandler h = dataHandler;
                    if (h != null) {
                        try {
                            h.onData(buf, n);
                        } catch (Throwable t) {
                            log.warn("DataHandler threw", t);
                        }
                    }
                } else if (n < 0) {
                    break;
                }
            }
        } catch (Exception e) {
            ConnectionState s = state.get();
            if (s != ConnectionState.DISCONNECTING && s != ConnectionState.DISCONNECTED) {
                log.error("Error en lectura serial", e);
                transition(ConnectionState.ERROR, e);
            }
        }
    }

    @Override
    public void send(byte[] data, int offset, int length) throws Exception {
        SerialPort p = port;
        if (p == null || !p.isOpen()) {
            throw new IOException("No conectado");
        }
        byte[] toWrite;
        if (offset == 0 && length == data.length) {
            toWrite = data;
        } else {
            toWrite = new byte[length];
            System.arraycopy(data, offset, toWrite, 0, length);
        }
        int written = p.writeBytes(toWrite, length);
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
        Thread t = readerThread;
        if (t != null) t.interrupt();
        cleanupPort();
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

    public void sendBreak(int durationMillis) throws InterruptedException {
        SerialPort p = port;
        if (p == null || !p.isOpen()) return;
        p.setBreak();
        try {
            Thread.sleep(durationMillis);
        } finally {
            p.clearBreak();
        }
    }

    public void setDTR(boolean on) {
        SerialPort p = port;
        if (p == null || !p.isOpen()) return;
        if (on) p.setDTR();
        else p.clearDTR();
        dtr = on;
    }

    public void setRTS(boolean on) {
        SerialPort p = port;
        if (p == null || !p.isOpen()) return;
        if (on) p.setRTS();
        else p.clearRTS();
        rts = on;
    }

    public SerialSignals readSignals() {
        SerialPort p = port;
        if (p == null || !p.isOpen()) {
            return new SerialSignals(false, false, false, false, false, false);
        }
        return new SerialSignals(dtr, rts, p.getCTS(), p.getDSR(), p.getDCD(), p.getRI());
    }

    public CircularByteBuffer history() {
        return history;
    }

    private void cleanupPort() {
        SerialPort p = port;
        if (p != null && p.isOpen()) {
            try {
                p.closePort();
            } catch (Exception e) {
                log.warn("Error cerrando puerto serial", e);
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

    private static int toJSerialStopBits(SerialConfig.StopBits sb) {
        return switch (sb) {
            case ONE -> SerialPort.ONE_STOP_BIT;
            case ONE_AND_HALF -> SerialPort.ONE_POINT_FIVE_STOP_BITS;
            case TWO -> SerialPort.TWO_STOP_BITS;
        };
    }

    private static int toJSerialParity(SerialConfig.Parity p) {
        return switch (p) {
            case NONE -> SerialPort.NO_PARITY;
            case ODD -> SerialPort.ODD_PARITY;
            case EVEN -> SerialPort.EVEN_PARITY;
            case MARK -> SerialPort.MARK_PARITY;
            case SPACE -> SerialPort.SPACE_PARITY;
        };
    }

    private static int toJSerialFlow(SerialConfig.FlowControl fc) {
        return switch (fc) {
            case NONE -> SerialPort.FLOW_CONTROL_DISABLED;
            case RTS_CTS -> SerialPort.FLOW_CONTROL_RTS_ENABLED | SerialPort.FLOW_CONTROL_CTS_ENABLED;
            case XON_XOFF -> SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED | SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED;
        };
    }
}