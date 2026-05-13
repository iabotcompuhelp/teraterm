package com.opentermx.serial.nativeio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de carga e integracion basica con la libreria nativa
 * opentermx_native.dll, empaquetada en src/main/resources/win32-x86-64/.
 */
class OpenTermXNativeLoadTest {

    private static final byte ESC = 0x1B;

    private static byte[] vt(String suffix) {
        byte[] s = suffix.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[s.length + 1];
        out[0] = ESC;
        System.arraycopy(s, 0, out, 1, s.length);
        return out;
    }

    @Test
    void libraryLoadsAndReportsVersion() {
        OpenTermXNative lib = OpenTermXNative.get();
        int version = lib.opentermx_version();
        assertEquals(1 << 16, version & 0xFFFF0000, "version mayor debe ser 1");
        assertNotEquals(0, version, "opentermx_version debe ser != 0");
    }

    @Test
    void strerrorReturnsKnownMessages() {
        OpenTermXNative lib = OpenTermXNative.get();
        assertEquals("ok", lib.opentermx_strerror(OpenTermXNative.OPENTERMX_OK));
        assertNotNull(lib.opentermx_strerror(OpenTermXNative.OPENTERMX_ERR_OPEN));
        assertTrue(lib.opentermx_strerror(OpenTermXNative.OPENTERMX_ERR_INVALID)
                .toLowerCase().contains("invalid"));
    }

    @Test
    void openingNonexistentPortReturnsError() {
        NativeSerialConfig cfg = new NativeSerialConfig();
        cfg.baudRate = 115200;
        cfg.dataBits = 8;
        cfg.stopBits = NativeSerialConfig.STOP_ONE;
        cfg.parity = NativeSerialConfig.PARITY_NONE;
        cfg.flowControl = NativeSerialConfig.FLOW_NONE;
        cfg.readTimeoutMs = 100;
        cfg.writeTimeoutMs = 100;
        IOException ex = assertThrows(IOException.class,
                () -> NativeSerialPort.open("COM_NO_EXISTE_999", cfg));
        assertNotNull(ex.getMessage());
    }

    @Test
    void terminalCreateFeedAndSnapshot() {
        try (NativeTerminal term = NativeTerminal.create(10, 40)) {
            assertEquals(10, term.rows());
            assertEquals(40, term.cols());

            byte[] data = vt("[2JHola");
            term.feed(data, data.length);

            NativeCell[] row0 = term.row(0);
            assertEquals('H', row0[0].codepoint);
            assertEquals('o', row0[1].codepoint);
            assertEquals('l', row0[2].codepoint);
            assertEquals('a', row0[3].codepoint);

            NativeCursor cur = term.cursor();
            assertEquals(0, cur.row);
            assertEquals(4, cur.col);
            assertEquals(1, cur.visible);
        }
    }

    @Test
    void terminalAppliesSgrColor() {
        try (NativeTerminal term = NativeTerminal.create(5, 20)) {
            byte[] data = vt("[31mX");
            term.feed(data, data.length);
            NativeCell[] row0 = term.row(0);
            assertEquals('X', row0[0].codepoint);
            assertEquals(0xCD0000, row0[0].fgRgb);
        }
    }

    @Test
    void terminalReportsDirtyRowsAfterFeed() {
        try (NativeTerminal term = NativeTerminal.create(6, 20)) {
            term.takeDirtyRows();

            byte[] data = "line1\r\nline2".getBytes(StandardCharsets.US_ASCII);
            term.feed(data, data.length);

            int[] dirty = term.takeDirtyRows();
            assertTrue(dirty.length >= 2, "se esperaba >=2 filas sucias, fueron: " + dirty.length);
            assertEquals(0, term.takeDirtyRows().length);
        }
    }
}