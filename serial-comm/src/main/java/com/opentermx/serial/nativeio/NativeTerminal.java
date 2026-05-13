package com.opentermx.serial.nativeio;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;

/**
 * Wrapper Java sobre el emulador VT nativo {@code opentermx_term_*}.
 */
public final class NativeTerminal implements AutoCloseable {

    private final OpenTermXNative lib;
    private volatile Pointer handle;
    private OpenTermXNative.WriteCallback writeCallback;   // mantener referencia: la JVM no debe GC-ear el callback

    private NativeTerminal(OpenTermXNative lib, Pointer handle) {
        this.lib = lib;
        this.handle = handle;
    }

    public static NativeTerminal create(int rows, int cols) {
        OpenTermXNative lib = OpenTermXNative.get();
        Pointer p = lib.opentermx_term_create(rows, cols);
        if (p == null) throw new IllegalStateException("opentermx_term_create devolvió NULL");
        return new NativeTerminal(lib, p);
    }

    public void resize(int rows, int cols) {
        ensureOpen();
        int rc = lib.opentermx_term_resize(handle, rows, cols);
        if (rc != OpenTermXNative.OPENTERMX_OK) {
            throw new IllegalStateException("resize fallo: " + lib.opentermx_strerror(rc));
        }
    }

    public void reset() {
        ensureOpen();
        lib.opentermx_term_reset(handle);
    }

    public void feed(byte[] data, int length) {
        ensureOpen();
        if (length < 0 || length > data.length) {
            throw new IllegalArgumentException("length fuera de rango");
        }
        lib.opentermx_term_feed(handle, data, new NativeLong(length));
    }

    public int rows() {
        ensureOpen();
        return lib.opentermx_term_rows(handle);
    }

    public int cols() {
        ensureOpen();
        return lib.opentermx_term_cols(handle);
    }

    public NativeCursor cursor() {
        ensureOpen();
        NativeCursor cur = new NativeCursor();
        lib.opentermx_term_get_cursor(handle, cur);
        return cur;
    }

    /**
     * Devuelve una snapshot de toda la pantalla como un array de celdas
     * indexable como {@code row * cols + col}.
     */
    public NativeCell[] snapshot() {
        ensureOpen();
        int rows = rows();
        int cols = cols();
        NativeCell[] cells = (NativeCell[]) new NativeCell().toArray(rows * cols);
        int rc = lib.opentermx_term_snapshot(handle, cells, new NativeLong((long) rows * cols));
        if (rc != OpenTermXNative.OPENTERMX_OK) {
            throw new IllegalStateException("snapshot fallo: " + lib.opentermx_strerror(rc));
        }
        return cells;
    }

    public NativeCell[] row(int rowIndex) {
        ensureOpen();
        int cols = cols();
        NativeCell[] cells = (NativeCell[]) new NativeCell().toArray(cols);
        int rc = lib.opentermx_term_get_row(handle, rowIndex, cells, new NativeLong(cols));
        if (rc != OpenTermXNative.OPENTERMX_OK) {
            throw new IllegalStateException("get_row fallo: " + lib.opentermx_strerror(rc));
        }
        return cells;
    }

    /**
     * Devuelve los índices de fila modificados desde la última llamada
     * (limpia las banderas dirty en la pasada).
     */
    public int[] takeDirtyRows() {
        ensureOpen();
        int rows = rows();
        int[] out = new int[rows];
        long n = lib.opentermx_term_take_dirty_rows(handle, out, new NativeLong(rows)).longValue();
        if (n == rows) return out;
        int[] trimmed = new int[(int) n];
        System.arraycopy(out, 0, trimmed, 0, (int) n);
        return trimmed;
    }

    /**
     * Instala un callback que recibe respuestas del emulador (DA, CPR, etc.).
     * El callback corre en un hilo de JNA: mantener el handler liviano.
     */
    public void setWriteCallback(java.util.function.Consumer<byte[]> sink) {
        ensureOpen();
        if (sink == null) {
            lib.opentermx_term_set_write_callback(handle, null, Pointer.NULL);
            writeCallback = null;
            return;
        }
        OpenTermXNative.WriteCallback cb = (data, length, user) -> {
            int n = length.intValue();
            if (n <= 0) return;
            byte[] buf = data.getByteArray(0, n);
            sink.accept(buf);
        };
        this.writeCallback = cb;
        lib.opentermx_term_set_write_callback(handle, cb, Pointer.NULL);
    }

    @Override
    public synchronized void close() {
        if (handle != null) {
            lib.opentermx_term_destroy(handle);
            handle = null;
            writeCallback = null;
        }
    }

    private void ensureOpen() {
        if (handle == null) throw new IllegalStateException("Terminal cerrado");
    }
}