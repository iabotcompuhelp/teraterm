package com.opentermx.tftp.common;

public class TftpException extends RuntimeException {
    private final ErrorCode errorCode;

    public TftpException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public TftpException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}