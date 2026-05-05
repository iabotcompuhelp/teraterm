package com.opentermx.macro;

public class MacroException extends RuntimeException {
    public MacroException(String message) {
        super(message);
    }

    public MacroException(String message, Throwable cause) {
        super(message, cause);
    }
}