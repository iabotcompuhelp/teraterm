package com.opentermx.serial;

public final class HexDumpFormatter {

    private HexDumpFormatter() {
    }

    public static String format(byte[] data, int offset, int length) {
        StringBuilder sb = new StringBuilder(length * 4);
        for (int i = 0; i < length; i += 16) {
            int chunk = Math.min(16, length - i);
            sb.append(String.format("%08x  ", i));
            for (int j = 0; j < 16; j++) {
                if (j < chunk) {
                    sb.append(String.format("%02x ", data[offset + i + j] & 0xFF));
                } else {
                    sb.append("   ");
                }
                if (j == 7) sb.append(' ');
            }
            sb.append(" |");
            for (int j = 0; j < chunk; j++) {
                int b = data[offset + i + j] & 0xFF;
                sb.append((b >= 0x20 && b < 0x7F) ? (char) b : '.');
            }
            sb.append("|\n");
        }
        return sb.toString();
    }
}