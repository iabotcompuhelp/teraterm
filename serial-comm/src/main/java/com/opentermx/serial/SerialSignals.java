package com.opentermx.serial;

public record SerialSignals(
        boolean dtr,
        boolean rts,
        boolean cts,
        boolean dsr,
        boolean dcd,
        boolean ri
) {
    public String describe() {
        return "DTR=" + bit(dtr) + " RTS=" + bit(rts) + " CTS=" + bit(cts)
                + " DSR=" + bit(dsr) + " DCD=" + bit(dcd) + " RI=" + bit(ri);
    }

    private static String bit(boolean b) {
        return b ? "1" : "0";
    }
}