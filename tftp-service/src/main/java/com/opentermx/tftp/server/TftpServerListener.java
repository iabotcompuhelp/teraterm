package com.opentermx.tftp.server;

@FunctionalInterface
public interface TftpServerListener {
    void onEvent(TftpServerEvent event);
}