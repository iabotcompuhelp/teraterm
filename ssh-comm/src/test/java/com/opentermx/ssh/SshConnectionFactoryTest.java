package com.opentermx.ssh;

import com.opentermx.common.connection.Connection;
import com.opentermx.common.connection.ConnectionState;
import com.opentermx.common.connection.ConnectionType;
import com.opentermx.common.connection.SerialConfig;
import com.opentermx.common.connection.SshAuth;
import com.opentermx.common.connection.SshConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshConnectionFactoryTest {

    private final SshConnectionFactory factory = new SshConnectionFactory();

    @Test
    void supportsSshTypeOnly() {
        assertTrue(factory.supports(ConnectionType.SSH));
        assertFalse(factory.supports(ConnectionType.SERIAL));
        assertFalse(factory.supports(ConnectionType.TELNET));
        assertFalse(factory.supports(ConnectionType.TCP_RAW));
    }

    @Test
    void createReturnsSshConnection() {
        SshConfig cfg = new SshConfig(
                "example.com",
                "user",
                new SshAuth.Password(new char[]{'p'}),
                22,
                60,
                false
        );
        Connection conn = factory.create(cfg);
        assertEquals(ConnectionType.SSH, conn.getConfig().getType());
        assertEquals(ConnectionState.DISCONNECTED, conn.getState());
        assertEquals("user@example.com:22", conn.getConfig().getDisplayName());
    }

    @Test
    void createRejectsWrongConfigType() {
        SerialConfig serial = new SerialConfig(
                "COM1",
                9600,
                8,
                SerialConfig.StopBits.ONE,
                SerialConfig.Parity.NONE,
                SerialConfig.FlowControl.NONE
        );
        assertThrows(IllegalArgumentException.class, () -> factory.create(serial));
    }
}