package com.opentermx.serial;

import com.opentermx.common.connection.Connection;
import com.opentermx.common.connection.ConnectionConfig;
import com.opentermx.common.connection.ConnectionFactory;
import com.opentermx.common.connection.ConnectionType;
import com.opentermx.common.connection.SerialConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Crea conexiones seriales eligiendo backend según la system property
 * {@code opentermx.serial.backend}:
 * <ul>
 *   <li>{@code jserialcomm} (default) — {@link SerialConnection}</li>
 *   <li>{@code native} — {@link NativeSerialConnection} usando {@code opentermx_native}</li>
 * </ul>
 * Si el backend nativo no carga (DLL ausente o incompatible), se hace fallback a jSerialComm con warning.
 */
public final class SerialConnectionFactory implements ConnectionFactory {

    private static final Logger log = LoggerFactory.getLogger(SerialConnectionFactory.class);

    /** Nombre de la system property para seleccionar el backend. */
    public static final String BACKEND_PROPERTY = "opentermx.serial.backend";

    public enum Backend {
        JSERIALCOMM,
        NATIVE;

        public static Backend fromSystemProperty() {
            return fromName(System.getProperty(BACKEND_PROPERTY));
        }

        /** Parsea un nombre libre (case-insensitive). Valores desconocidos o null → {@link #JSERIALCOMM}. */
        public static Backend fromName(String name) {
            if (name == null || name.isBlank()) return JSERIALCOMM;
            return switch (name.trim().toLowerCase()) {
                case "native", "opentermx", "jna" -> NATIVE;
                default -> JSERIALCOMM;
            };
        }
    }

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
        return createSerial(serial);
    }

    /** Crea la conexión serial honrando {@link #BACKEND_PROPERTY}. Hace fallback a jSerialComm si native falla. */
    public static SerialPortConnection createSerial(SerialConfig config) {
        return create(config, Backend.fromSystemProperty());
    }

    /** Igual que {@link #createSerial(SerialConfig)} pero con backend explícito (sin leer system property). */
    public static SerialPortConnection create(SerialConfig config, Backend backend) {
        if (backend == Backend.NATIVE && isNativeAvailable()) {
            return new NativeSerialConnection(config);
        }
        return new SerialConnection(config);
    }

    private static volatile Boolean nativeAvailable;

    /** Comprueba (una vez, con cache) que la librería {@code opentermx_native} puede cargarse. */
    public static boolean isNativeAvailable() {
        Boolean cached = nativeAvailable;
        if (cached != null) return cached;
        synchronized (SerialConnectionFactory.class) {
            if (nativeAvailable == null) {
                try {
                    com.opentermx.serial.nativeio.OpenTermXNative.get().opentermx_version();
                    nativeAvailable = Boolean.TRUE;
                } catch (Throwable t) {
                    log.warn("Backend serial nativo no disponible ({}); se usará jSerialComm", t.toString());
                    nativeAvailable = Boolean.FALSE;
                }
            }
            return nativeAvailable;
        }
    }
}