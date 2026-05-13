package com.opentermx.serial;

import com.opentermx.common.connection.ConnectionState;
import com.opentermx.common.connection.SerialConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Smoke tests para NativeSerialConnection. Si el DLL nativo no está disponible
 * en este entorno (Linux/Mac sin compilar), los tests se saltean con Assumptions.
 */
class NativeSerialConnectionTest {

    @BeforeEach
    void requireNativeBackend() {
        Assumptions.assumeTrue(
                SerialConnectionFactory.isNativeAvailable(),
                "Backend nativo no disponible en este entorno; saltando");
    }

    private static SerialConfig invalidConfig() {
        return new SerialConfig("COM_NO_EXISTE_999", 115200, 8,
                SerialConfig.StopBits.ONE,
                SerialConfig.Parity.NONE,
                SerialConfig.FlowControl.NONE,
                "");
    }

    @Test
    void connectingToMissingPortLeavesStateInError() {
        try (NativeSerialConnection conn = new NativeSerialConnection(invalidConfig())) {
            assertEquals(ConnectionState.DISCONNECTED, conn.getState());
            assertThrows(Exception.class, conn::connect);
            assertEquals(ConnectionState.ERROR, conn.getState());
        }
    }

    @Test
    void readSignalsWhenDisconnectedReturnsAllLow() {
        try (NativeSerialConnection conn = new NativeSerialConnection(invalidConfig())) {
            SerialSignals s = conn.readSignals();
            assertEquals(false, s.cts());
            assertEquals(false, s.dsr());
            assertEquals(false, s.dcd());
            assertEquals(false, s.ri());
        }
    }

    @Test
    void historyIsNonNullAndEmptyInitially() {
        try (NativeSerialConnection conn = new NativeSerialConnection(invalidConfig())) {
            assertNotEquals(null, conn.history());
            assertEquals(0, conn.history().size());
        }
    }
}