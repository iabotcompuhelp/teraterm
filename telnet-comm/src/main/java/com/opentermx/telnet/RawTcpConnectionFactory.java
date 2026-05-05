package com.opentermx.telnet;

import com.opentermx.common.connection.Connection;
import com.opentermx.common.connection.ConnectionConfig;
import com.opentermx.common.connection.ConnectionFactory;
import com.opentermx.common.connection.ConnectionType;
import com.opentermx.common.connection.TcpRawConfig;

public final class RawTcpConnectionFactory implements ConnectionFactory {

    @Override
    public boolean supports(ConnectionType type) {
        return type == ConnectionType.TCP_RAW;
    }

    @Override
    public Connection create(ConnectionConfig config) {
        if (!(config instanceof TcpRawConfig tcp)) {
            throw new IllegalArgumentException(
                    "RawTcpConnectionFactory requiere TcpRawConfig pero recibió " + config.getClass());
        }
        return new RawTcpConnection(tcp);
    }
}