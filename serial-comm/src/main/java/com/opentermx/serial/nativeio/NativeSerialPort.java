package com.opentermx.serial.nativeio;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.NativeLongByReference;
import com.sun.jna.ptr.PointerByReference;

import java.io.IOException;

/**
 * Wrapper Java idiomático sobre las funciones {@code opentermx_serial_*}
 * de la librería nativa. Disponible cuando {@code opentermx_native} está
 * cargada en {@code java.library.path}.
 */
public final class NativeSerialPort implements AutoCloseable {

    private final OpenTermXNative lib;
    private volatile Pointer handle;
    private final String portName;

    private NativeSerialPort(OpenTermXNative lib, Pointer handle, String portName) {
        this.lib = lib;
        this.handle = handle;
        this.portName = portName;
    }

    public static NativeSerialPort open(String portName, NativeSerialConfig config) throws IOException {
        OpenTermXNative lib = OpenTermXNative.get();
        PointerByReference out = new PointerByReference();
        int rc = lib.opentermx_serial_open(portName, config, out);
        if (rc != OpenTermXNative.OPENTERMX_OK) {
            throw new IOException("Error abriendo " + portName + ": " + lib.opentermx_strerror(rc));
        }
        return new NativeSerialPort(lib, out.getValue(), portName);
    }

    public String portName() {
        return portName;
    }

    public int read(byte[] buffer) throws IOException {
        ensureOpen();
        NativeLongByReference outRead = new NativeLongByReference();
        int rc = lib.opentermx_serial_read(handle, buffer, new NativeLong(buffer.length), outRead);
        if (rc == OpenTermXNative.OPENTERMX_ERR_TIMEOUT) return 0;
        if (rc != OpenTermXNative.OPENTERMX_OK) throw ioException("read", rc);
        return outRead.getValue().intValue();
    }

    public int write(byte[] buffer, int length) throws IOException {
        ensureOpen();
        if (length < 0 || length > buffer.length) {
            throw new IllegalArgumentException("length fuera de rango");
        }
        NativeLongByReference outWritten = new NativeLongByReference();
        int rc = lib.opentermx_serial_write(handle, buffer, new NativeLong(length), outWritten);
        if (rc != OpenTermXNative.OPENTERMX_OK) throw ioException("write", rc);
        return outWritten.getValue().intValue();
    }

    public void flush() throws IOException {
        ensureOpen();
        int rc = lib.opentermx_serial_flush(handle);
        if (rc != OpenTermXNative.OPENTERMX_OK) throw ioException("flush", rc);
    }

    public void setDTR(boolean on) throws IOException {
        ensureOpen();
        int rc = lib.opentermx_serial_set_dtr(handle, on ? 1 : 0);
        if (rc != OpenTermXNative.OPENTERMX_OK) throw ioException("setDTR", rc);
    }

    public void setRTS(boolean on) throws IOException {
        ensureOpen();
        int rc = lib.opentermx_serial_set_rts(handle, on ? 1 : 0);
        if (rc != OpenTermXNative.OPENTERMX_OK) throw ioException("setRTS", rc);
    }

    public void sendBreak(int durationMs) throws IOException {
        ensureOpen();
        int rc = lib.opentermx_serial_send_break(handle, durationMs);
        if (rc != OpenTermXNative.OPENTERMX_OK) throw ioException("sendBreak", rc);
    }

    public int readSignals() throws IOException {
        ensureOpen();
        IntByReference out = new IntByReference();
        int rc = lib.opentermx_serial_get_signals(handle, out);
        if (rc != OpenTermXNative.OPENTERMX_OK) throw ioException("getSignals", rc);
        return out.getValue();
    }

    public int available() throws IOException {
        ensureOpen();
        NativeLongByReference out = new NativeLongByReference();
        int rc = lib.opentermx_serial_available(handle, out);
        if (rc != OpenTermXNative.OPENTERMX_OK) throw ioException("available", rc);
        return out.getValue().intValue();
    }

    public void reconfigure(NativeSerialConfig config) throws IOException {
        ensureOpen();
        int rc = lib.opentermx_serial_configure(handle, config);
        if (rc != OpenTermXNative.OPENTERMX_OK) throw ioException("configure", rc);
    }

    @Override
    public synchronized void close() {
        if (handle != null) {
            lib.opentermx_serial_close(handle);
            handle = null;
        }
    }

    private void ensureOpen() throws IOException {
        if (handle == null) throw new IOException("Puerto " + portName + " cerrado");
    }

    private IOException ioException(String op, int rc) {
        return new IOException(op + " fallo: " + lib.opentermx_strerror(rc) + " (" + rc + ")");
    }
}