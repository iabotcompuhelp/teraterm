package com.opentermx.tftp.server;

import java.net.SocketAddress;
import java.time.Instant;

public record TftpServerEvent(
        Instant timestamp,
        Kind kind,
        SocketAddress peer,
        String file,
        long bytes,
        String message) {

    public enum Kind {
        SERVER_STARTED,
        SERVER_STOPPED,
        TRANSFER_STARTED,
        TRANSFER_COMPLETED,
        TRANSFER_FAILED,
        INFO
    }

    public static TftpServerEvent started(int port) {
        return new TftpServerEvent(Instant.now(), Kind.SERVER_STARTED, null, null, 0,
                "Server listening on UDP " + port);
    }

    public static TftpServerEvent stopped() {
        return new TftpServerEvent(Instant.now(), Kind.SERVER_STOPPED, null, null, 0, "Server stopped");
    }

    public static TftpServerEvent transferStarted(SocketAddress peer, String file, String op) {
        return new TftpServerEvent(Instant.now(), Kind.TRANSFER_STARTED, peer, file, 0,
                op + " " + file);
    }

    public static TftpServerEvent transferCompleted(SocketAddress peer, String file, long bytes) {
        return new TftpServerEvent(Instant.now(), Kind.TRANSFER_COMPLETED, peer, file, bytes,
                "Completed (" + bytes + " bytes)");
    }

    public static TftpServerEvent transferFailed(SocketAddress peer, String file, String reason) {
        return new TftpServerEvent(Instant.now(), Kind.TRANSFER_FAILED, peer, file, 0, reason);
    }

    public static TftpServerEvent info(String message) {
        return new TftpServerEvent(Instant.now(), Kind.INFO, null, null, 0, message);
    }
}