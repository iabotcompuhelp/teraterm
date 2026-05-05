package com.opentermx.ssh;

import com.jcraft.jsch.UserInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SimpleUserInfo implements UserInfo {

    private static final Logger log = LoggerFactory.getLogger(SimpleUserInfo.class);

    private final String password;
    private final String passphrase;

    SimpleUserInfo(String password, char[] passphrase) {
        this.password = password;
        this.passphrase = passphrase != null ? new String(passphrase) : null;
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
        // Host-key decisions are routed through TofuHostKeyRepository before this
        // method is reached. Anything else (changed-config prompts, etc.) is rejected.
        log.warn("SSH UserInfo.promptYesNo invoked unexpectedly: {}", message);
        return false;
    }

    @Override
    public void showMessage(String message) {
        log.info("SSH server message: {}", message);
    }
}