package com.opentermx.tftp.common;

import java.util.Locale;

public enum TransferMode {
    NETASCII("netascii"),
    OCTET("octet");

    private final String wireName;

    TransferMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static TransferMode parse(String s) {
        if (s == null) throw new IllegalArgumentException("mode is null");
        String lower = s.toLowerCase(Locale.ROOT);
        for (TransferMode m : values()) if (m.wireName.equals(lower)) return m;
        throw new IllegalArgumentException("Unsupported TFTP mode: " + s);
    }
}