package com.opentermx.ssh;

import com.opentermx.common.connection.Connection;
import com.opentermx.common.connection.ConnectionConfig;
import com.opentermx.common.connection.ConnectionFactory;
import com.opentermx.common.connection.ConnectionType;
import com.opentermx.common.connection.SshConfig;

public final class SshConnectionFactory implements ConnectionFactory {

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
        return new SshConnection(ssh);
    }
}