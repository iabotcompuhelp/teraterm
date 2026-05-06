package com.opentermx.tftp.common;

public enum Opcode {
    RRQ(1),
    WRQ(2),
    DATA(3),
    ACK(4),
    ERROR(5),
    OACK(6);

    private final int code;

    Opcode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static Opcode of(int code) {
        for (Opcode op : values()) if (op.code == code) return op;
        throw new IllegalArgumentException("Unknown TFTP opcode: " + code);
    }
}