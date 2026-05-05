package com.opentermx.ssh;

import com.jcraft.jsch.UserInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SimpleUserInfo implements UserInfo {

    private static final Logger log = LoggerFactory.getLogger(SimpleUserInfo.class);

    private final String password;
    private final String passphrase;
    private final boolean autoAcceptHostKey;

    SimpleUserInfo(String password, char[] passphrase, boolean autoAcceptHostKey) {
        this.password = password;
        this.passphrase = passphrase != null ? new String(passphrase) : null;
        this.autoAcceptHostKey = autoAcceptHostKey;
    }

    @Override
    public String getPassphrase() {
        return passphrase;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public boolean promptPassword(String message) {
        return password != null;
    }

    @Override
    public boolean promptPassphrase(String message) {
        return passphrase != null;
    }

    @Override
    public boolean promptYesNo(String message) {
        log.warn("SSH host key prompt: {} (auto-accept={})", message, autoAcceptHostKey);
        return autoAcceptHostKey;
    }

    @Override
    public void showMessage(String message) {
        log.info("SSH server message: {}", message);
    }
}