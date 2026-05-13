package com.opentermx.serial.nativeio;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.PointerType;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.Objects;

/**
 * Binding JNA hacia la librería nativa {@code opentermx_native}
 * (ver native/src/serial_native.h y native/src/terminal_emu.h).
 *
 * <p>Cargar con {@link #get()}. Si la librería no está disponible, lanza
 * {@link UnsatisfiedLinkError} en la primera invocación.</p>
 */
public interface OpenTermXNative extends Library {

    String LIBRARY_NAME = "opentermx_native";

    /* ---------- Códigos de retorno ----------------------------------- */
    int OPENTERMX_OK             =  0;
    int OPENTERMX_ERR_INVALID    = -1;
    int OPENTERMX_ERR_OPEN       = -2;
    int OPENTERMX_ERR_CONFIG     = -3;
    int OPENTERMX_ERR_IO         = -4;
    int OPENTERMX_ERR_TIMEOUT    = -5;
    int OPENTERMX_ERR_CLOSED     = -6;
    int OPENTERMX_ERR_NOT_FOUND  = -7;

    /* ---------- Atributos de celda ----------------------------------- */
    int ATTR_BOLD          = 1 << 0;
    int ATTR_DIM           = 1 << 1;
    int ATTR_ITALIC        = 1 << 2;
    int ATTR_UNDERLINE     = 1 << 3;
    int ATTR_BLINK         = 1 << 4;
    int ATTR_REVERSE       = 1 << 5;
    int ATTR_INVISIBLE     = 1 << 6;
    int ATTR_STRIKETHROUGH = 1 << 7;

    /* ---------- Líneas de control ------------------------------------ */
    int SIGNAL_CTS = 1 << 0;
    int SIGNAL_DSR = 1 << 1;
    int SIGNAL_DCD = 1 << 2;
    int SIGNAL_RI  = 1 << 3;
    int SIGNAL_DTR = 1 << 4;
    int SIGNAL_RTS = 1 << 5;

    /* ---------- Handle opaco como PointerType ------------------------ */
    final class SerialHandle extends PointerType {
        public SerialHandle() { super(); }
        public SerialHandle(Pointer p) { super(p); }
    }

    final class TermHandle extends PointerType {
        public TermHandle() { super(); }
        public TermHandle(Pointer p) { super(p); }
    }

    /* ---------- Callback para emulación ------------------------------ */
    interface WriteCallback extends Callback {
        void invoke(Pointer data, com.sun.jna.NativeLong length, Pointer user);
    }

    /* ---------- Funciones genéricas ---------------------------------- */
    int opentermx_version();
    String opentermx_strerror(int code);

    /* ---------- Serial ----------------------------------------------- */
    int opentermx_serial_open(String portName,
                              NativeSerialConfig config,
                              PointerByReference outHandle);

    void opentermx_serial_close(Pointer handle);

    int opentermx_serial_configure(Pointer handle, NativeSerialConfig config);

    int opentermx_serial_read(Pointer handle,
                              byte[] buffer,
                              com.sun.jna.NativeLong length,
                              com.sun.jna.ptr.NativeLongByReference outRead);

    int opentermx_serial_write(Pointer handle,
                               byte[] buffer,
                               com.sun.jna.NativeLong length,
                               com.sun.jna.ptr.NativeLongByReference outWritten);

    int opentermx_serial_flush(Pointer handle);

    int opentermx_serial_set_dtr(Pointer handle, int on);

    int opentermx_serial_set_rts(Pointer handle, int on);

    int opentermx_serial_send_break(Pointer handle, int durationMs);

    int opentermx_serial_get_signals(Pointer handle, IntByReference outSignals);

    int opentermx_serial_available(Pointer handle,
                                   com.sun.jna.ptr.NativeLongByReference outAvailable);

    /* ---------- Terminal --------------------------------------------- */
    Pointer opentermx_term_create(int rows, int cols);

    void opentermx_term_destroy(Pointer term);

    int opentermx_term_resize(Pointer term, int rows, int cols);

    void opentermx_term_reset(Pointer term);

    com.sun.jna.NativeLong opentermx_term_feed(Pointer term,
                                               byte[] data,
                                               com.sun.jna.NativeLong length);

    int opentermx_term_rows(Pointer term);

    int opentermx_term_cols(Pointer term);

    void opentermx_term_get_cursor(Pointer term, NativeCursor outCursor);

    int opentermx_term_snapshot(Pointer term,
                                NativeCell[] outCells,
                                com.sun.jna.NativeLong cellCount);

    int opentermx_term_get_row(Pointer term,
                               int row,
                               NativeCell[] outCells,
                               com.sun.jna.NativeLong cellCount);

    com.sun.jna.NativeLong opentermx_term_take_dirty_rows(Pointer term,
                                                          int[] outRows,
                                                          com.sun.jna.NativeLong maxRows);

    void opentermx_term_set_write_callback(Pointer term,
                                           WriteCallback callback,
                                           Pointer user);

    /* ----------------------------------------------------------------- */
    /* Carga perezosa de la librería                                      */
    /* ----------------------------------------------------------------- */
    final class Holder {
        private static volatile OpenTermXNative instance;
        private Holder() {}

        static OpenTermXNative load() {
            OpenTermXNative i = instance;
            if (i != null) return i;
            synchronized (Holder.class) {
                if (instance == null) {
                    instance = Native.load(LIBRARY_NAME, OpenTermXNative.class);
                }
                return instance;
            }
        }
    }

    /** Carga la librería bajo demanda. */
    static OpenTermXNative get() {
        return Objects.requireNonNull(Holder.load(), "opentermx_native no cargada");
    }
}