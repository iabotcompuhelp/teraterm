package com.opentermx.tftp.common;

@FunctionalInterface
public interface TransferProgress {

    void onProgress(long bytesTransferred, long totalBytes);

    static TransferProgress noop() {
        return (b, t) -> {};
    }
}