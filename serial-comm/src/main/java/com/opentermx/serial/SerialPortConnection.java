package com.opentermx.serial;

import com.opentermx.common.connection.Connection;

/**
 * API de un puerto serial (independiente del backend: jSerialComm o nativo).
 *
 * <p>Permite que el resto de la app maneje señales DTR/RTS, BREAK e historial sin
 * conocer la implementación concreta detrás de {@link com.opentermx.common.connection.Connection}.
 */
public interface SerialPortConnection extends Connection {

    void sendBreak(int durationMillis) throws InterruptedException;

    void setDTR(boolean on);

    void setRTS(boolean on);

    SerialSignals readSignals();

    CircularByteBuffer history();
}