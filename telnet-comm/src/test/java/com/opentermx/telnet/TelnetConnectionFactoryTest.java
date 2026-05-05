package com.opentermx.telnet;

import com.opentermx.common.connection.Connection;
import com.opentermx.common.connection.ConnectionState;
import com.opentermx.common.connection.ConnectionType;
import com.opentermx.common.connection.SerialConfig;
import com.opentermx.common.connection.TelnetConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelnetConnectionFactoryTest {

    private final TelnetConnectionFactory factory = new TelnetConnectionFactory();

    @Test
    void supportsTelnetTypeOnly() {
        assertTrue(factory.supports(ConnectionType.TELNET));
        assertFalse(factory.supports(ConnectionType.SSH));
        assertFalse(factory.supports(ConnectionType.SERIAL));
        assertFalse(factory.supports(ConnectionType.TCP_RAW));
    }

    @Test
    void createReturnsTelnetConnection() {
        TelnetConfig cfg = new TelnetConfig("example.com", 23, false);
        Connection conn = factory.create(cfg);
        assertEquals(ConnectionType.TELNET, conn.getConfig().getType());
        assertEquals(ConnectionState.DISCONNECTED, conn.getState());
        assertEquals("telnet://example.com:23", conn.getConfig().getDisplayName());
    }

    @Test
    void honoursTlsFlag() {
        TelnetConfig cfg = new TelnetConfig("example.com", 992, true);
        Connection conn = factory.create(cfg);
        assertTrue(((TelnetConfig) conn.getConfig()).getUseTls());
    }

    @Test
    void createRejectsWrongConfigType() {
        SerialConfig serial = new SerialConfig(
                "COM1", 9600, 8,
                SerialConfig.StopBits.ONE,
                SerialConfig.Parity.NONE,
                SerialConfig.FlowControl.NONE
        );
        assertThrows(IllegalArgumentException.class, () -> factory.create(serial));
    }
}