package com.opentermx.serial;

import com.opentermx.common.connection.Connection;
import com.opentermx.common.connection.ConnectionConfig;
import com.opentermx.common.connection.ConnectionFactory;
import com.opentermx.common.connection.ConnectionType;
import com.opentermx.common.connection.SerialConfig;

public final class SerialConnectionFactory implements ConnectionFactory {

    @Override
    public boolean supports(ConnectionType type) {
        return type == ConnectionType.SERIAL;
    }

    @Override
    public Connection create(ConnectionConfig config) {
        if (!(config instanceof SerialConfig serial)) {
            throw new IllegalArgumentException(
                    "SerialConnectionFactory requiere SerialConfig pero recibió " + config.getClass());
        }
        return new SerialConnection(serial);
    }
}