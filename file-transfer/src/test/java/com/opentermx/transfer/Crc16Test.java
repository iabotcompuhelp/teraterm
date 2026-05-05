package com.opentermx.transfer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Crc16Test {

    @Test
    void emptyInputProducesZero() {
        assertEquals(0, Crc16.compute(new byte[0], 0, 0));
    }

    @Test
    void knownVectorOneTwoThreeNine() {
        // CRC-16/XMODEM of "123456789" (ASCII) is 0x31C3 per CRC catalogue.
        byte[] data = "123456789".getBytes();
        assertEquals(0x31C3, Crc16.compute(data, 0, data.length));
    }

    @Test
    void honoursOffsetAndLength() {
        byte[] data = "XX123456789YY".getBytes();
        assertEquals(0x31C3, Crc16.compute(data, 2, 9));
    }
}