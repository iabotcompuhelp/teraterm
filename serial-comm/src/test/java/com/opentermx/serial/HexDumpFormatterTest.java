package com.opentermx.serial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HexDumpFormatterTest {

    @Test
    void formatsAsciiAndHex() {
        String dump = HexDumpFormatter.format("Hello".getBytes(), 0, 5);
        assertTrue(dump.contains("48 65 6c 6c 6f"), "expected hex bytes for 'Hello'");
        assertTrue(dump.contains("|Hello|"), "expected ASCII column with 'Hello'");
    }

    @Test
    void replacesNonPrintableWithDot() {
        byte[] data = new byte[]{(byte) 0x00, (byte) 0x1B, (byte) 0xFF};
        String dump = HexDumpFormatter.format(data, 0, data.length);
        assertTrue(dump.contains("|...|"));
    }
}