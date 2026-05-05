package com.opentermx.ssh;

import com.opentermx.common.connection.Connection;
import com.opentermx.common.connection.ConnectionConfig;
import com.opentermx.common.connection.ConnectionFactory;
import com.opentermx.common.connection.ConnectionType;
import com.opentermx.common.connection.HostKeyVerifier;
import com.opentermx.common.connection.RejectAllHostKeyVerifier;
import com.opentermx.common.connection.SshConfig;

public final class SshConnectionFactory implements ConnectionFactory {

    private final HostKeyVerifier hostKeyVerifier;

    public SshConnectionFactory() {
        this(RejectAllHostKeyVerifier.INSTANCE);
    }

    public SshConnectionFactory(HostKeyVerifier hostKeyVerifier) {
        this.hostKeyVerifier = hostKeyVerifier != null ? hostKeyVerifier : RejectAllHostKeyVerifier.INSTANCE;
    }

    @Override
    public boolean supports(ConnectionType type) {
        return type == ConnectionType.SSH;
    }

    @Override
    public Connection create(ConnectionConfig config) {
        if (!(config instanceof SshConfig ssh)) {
            throw new IllegalArgumentException(
                    "SshConnectionFactory requiere SshConfig pero recibió " + config.getClass());
        }
        return new SshConnection(ssh, hostKeyVerifier);
    }
}