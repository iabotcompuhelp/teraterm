package com.opentermx.tftp.common;

public enum ErrorCode {
    NOT_DEFINED(0, "Not defined"),
    FILE_NOT_FOUND(1, "File not found"),
    ACCESS_VIOLATION(2, "Access violation"),
    DISK_FULL(3, "Disk full or allocation exceeded"),
    ILLEGAL_OPERATION(4, "Illegal TFTP operation"),
    UNKNOWN_TID(5, "Unknown transfer ID"),
    FILE_EXISTS(6, "File already exists"),
    NO_SUCH_USER(7, "No such user"),
    OPTION_NEGOTIATION(8, "Option negotiation failed");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    public static ErrorCode of(int code) {
        for (ErrorCode ec : values()) if (ec.code == code) return ec;
        return NOT_DEFINED;
    }
}