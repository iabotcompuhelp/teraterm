package com.opentermx.serial.nativeio;

import com.sun.jna.Structure;

import java.util.List;

/**
 * Mapeo JNA de {@code opentermx_serial_config_t} (ver native/src/serial_native.h).
 *
 * <p>Los campos están alineados al orden y tipo del struct C; no reordenar.</p>
 */
@Structure.FieldOrder({
        "baudRate", "dataBits", "stopBits", "parity",
        "flowControl", "readTimeoutMs", "writeTimeoutMs"
})
public class NativeSerialConfig extends Structure {

    public static final int PARITY_NONE  = 0;
    public static final int PARITY_ODD   = 1;
    public static final int PARITY_EVEN  = 2;
    public static final int PARITY_MARK  = 3;
    public static final int PARITY_SPACE = 4;

    public static final int STOP_ONE      = 0;
    public static final int STOP_ONE_HALF = 1;
    public static final int STOP_TWO      = 2;

    public static final int FLOW_NONE    = 0;
    public static final int FLOW_RTSCTS  = 1;
    public static final int FLOW_XONXOFF = 2;

    public int baudRate;
    public int dataBits;
    public int stopBits;
    public int parity;
    public int flowControl;
    public int readTimeoutMs;
    public int writeTimeoutMs;

    public NativeSerialConfig() {
        super();
    }

    public static class ByReference extends NativeSerialConfig implements Structure.ByReference {}
    public static class ByValue extends NativeSerialConfig implements Structure.ByValue {}

    @Override
    protected List<String> getFieldOrder() {
        return List.of("baudRate", "dataBits", "stopBits", "parity",
                "flowControl", "readTimeoutMs", "writeTimeoutMs");
    }
}