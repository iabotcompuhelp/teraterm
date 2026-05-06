package com.opentermx.tftp.client;

import com.opentermx.tftp.common.Packets;
import com.opentermx.tftp.common.TransferMode;

public record TftpClientOptions(
        TransferMode mode,
        int blockSize,
        int timeoutSeconds,
        int maxRetries,
        boolean negotiateTsize) {

    public TftpClientOptions {
        if (blockSize < Packets.MIN_BLOCK_SIZE) blockSize = Packets.MIN_BLOCK_SIZE;
        if (blockSize > Packets.MAX_BLOCK_SIZE) blockSize = Packets.MAX_BLOCK_SIZE;
        if (timeoutSeconds < 1) timeoutSeconds = 1;
        if (maxRetries < 1) maxRetries = 1;
    }

    public static TftpClientOptions defaults() {
        return new TftpClientOptions(TransferMode.OCTET, Packets.DEFAULT_BLOCK_SIZE, 5, 5, true);
    }
}