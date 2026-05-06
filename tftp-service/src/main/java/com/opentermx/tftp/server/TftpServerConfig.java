package com.opentermx.tftp.server;

import java.nio.file.Path;
import java.util.Objects;

public record TftpServerConfig(
        int port,
        Path rootDirectory,
        boolean allowGet,
        boolean allowPut,
        int timeoutSeconds,
        int maxRetries) {

    public TftpServerConfig {
        Objects.requireNonNull(rootDirectory, "rootDirectory");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port out of range: " + port);
        if (timeoutSeconds < 1) timeoutSeconds = 5;
        if (maxRetries < 1) maxRetries = 5;
    }

    public static TftpServerConfig defaults(Path rootDirectory) {
        return new TftpServerConfig(69, rootDirectory, true, false, 5, 5);
    }
}