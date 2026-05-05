package com.opentermx.transfer;

public final class Crc16 {

    private Crc16() {
    }

    public static int compute(byte[] data, int offset, int length) {
        int crc = 0;
        for (int i = 0; i < length; i++) {
            crc ^= (data[offset + i] & 0xFF) << 8;
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x8000) != 0) crc = (crc << 1) ^ 0x1021;
                else crc <<= 1;
            }
            crc &= 0xFFFF;
        }
        return crc;
    }
}