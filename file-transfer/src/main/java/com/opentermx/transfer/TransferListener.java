package com.opentermx.transfer;

public interface TransferListener {

    /** -1 totalBytes means total is unknown (XMODEM receiver). */
    void onProgress(long bytesTransferred, long totalBytes);

    default void onMessage(String message) {}

    default boolean isCancelled() {
        return false;
    }

    TransferListener NOOP = new TransferListener() {
        @Override public void onProgress(long bytesTransferred, long totalBytes) {}
    };
}