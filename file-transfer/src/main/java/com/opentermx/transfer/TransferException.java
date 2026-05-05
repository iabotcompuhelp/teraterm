package com.opentermx.transfer;

import java.io.IOException;

public class TransferException extends IOException {
    public TransferException(String message) {
        super(message);
    }

    public TransferException(String message, Throwable cause) {
        super(message, cause);
    }
}