package com.opentermx.serial;

import com.opentermx.common.connection.SerialConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SerialConnectionFactoryTest {

    private String previous;

    @BeforeEach
    void snapshotProperty() {
        previous = System.getProperty(SerialConnectionFactory.BACKEND_PROPERTY);
        System.clearProperty(SerialConnectionFactory.BACKEND_PROPERTY);
    }

    @AfterEach
    void restoreProperty() {
        if (previous == null) {
            System.clearProperty(SerialConnectionFactory.BACKEND_PROPERTY);
        } else {
            System.setProperty(SerialConnectionFactory.BACKEND_PROPERTY, previous);
        }
    }

    private static SerialConfig dummyConfig() {
        return new SerialConfig("COM_TEST", 115200, 8,
                SerialConfig.StopBits.ONE,
                SerialConfig.Parity.NONE,
                SerialConfig.FlowControl.NONE,
                "");
    }

    @Test
    void defaultBackendIsJSerialComm() {
        assertSame(SerialConnectionFactory.Backend.JSERIALCOMM,
                SerialConnectionFactory.Backend.fromSystemProperty());
        SerialPortConnection conn = SerialConnectionFactory.createSerial(dummyConfig());
        assertNotNull(conn);
        assertInstanceOf(SerialConnection.class, conn,
                "default debe ser SerialConnection (jSerialComm)");
        conn.close();
    }

    @Test
    void nativeBackendIsSelectedWhenSystemPropertyAndDllPresent() {
        System.setProperty(SerialConnectionFactory.BACKEND_PROPERTY, "native");
        assertSame(SerialConnectionFactory.Backend.NATIVE,
                SerialConnectionFactory.Backend.fromSystemProperty());

        SerialPortConnection conn = SerialConnectionFactory.createSerial(dummyConfig());
        try {
            if (SerialConnectionFactory.isNativeAvailable()) {
                assertInstanceOf(NativeSerialConnection.class, conn,
                        "con backend=native y DLL disponible debe retornar NativeSerialConnection");
            } else {
                assertInstanceOf(SerialConnection.class, conn,
                        "sin DLL debe hacer fallback a jSerialComm");
            }
        } finally {
            conn.close();
        }
    }

    @Test
    void unknownBackendFallsBackToJSerialComm() {
        System.setProperty(SerialConnectionFactory.BACKEND_PROPERTY, "rust-experimental");
        assertSame(SerialConnectionFactory.Backend.JSERIALCOMM,
                SerialConnectionFactory.Backend.fromSystemProperty());
    }
}