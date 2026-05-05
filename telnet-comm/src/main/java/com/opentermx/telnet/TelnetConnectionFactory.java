package com.opentermx.telnet;

import com.opentermx.common.connection.Connection;
import com.opentermx.common.connection.ConnectionConfig;
import com.opentermx.common.connection.ConnectionFactory;
import com.opentermx.common.connection.ConnectionType;
import com.opentermx.common.connection.TelnetConfig;

public final class TelnetConnectionFactory implements ConnectionFactory {

    @Override
    public boolean supports(ConnectionType type) {
        return type == ConnectionType.TELNET;
    }

    @Override
    public Connection create(ConnectionConfig config) {
        if (!(config instanceof TelnetConfig telnet)) {
            throw new IllegalArgumentException(
                    "TelnetConnectionFactory requiere TelnetConfig pero recibió " + config.getClass());
        }
        return new TelnetConnection(telnet);
    }
}