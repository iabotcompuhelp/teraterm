package com.opentermx.app.settings

import com.fasterxml.jackson.annotation.JsonInclude
import com.opentermx.common.crypto.EncryptedValue

/**
 * Conexión a PostgreSQL para la capa de telemetría (Fase 3 del plan). Default OFF y
 * `localhost` como placeholder — el operador la habilita editando esta sección de los
 * settings (`~/.opentermx/settings.json` → `database`).
 *
 * El password NUNCA va en texto plano: o bien [password] cifrado con
 * [com.opentermx.common.crypto.SecretCipher] (mismo esquema que el resto de secretos de
 * la app), o bien la variable de entorno `OPENTERMX_DB_PASSWORD` si el campo está vacío.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DatabaseSettings(
    val enabled: Boolean = false,
    val host: String = "localhost",
    val port: Int = 5432,
    val database: String = "opentermx",
    val username: String = "opentermx",
    val password: EncryptedValue? = null,
    /** Scheduler de muestreo periódico de interfaces sobre las sesiones activas. */
    val schedulerEnabled: Boolean = false,
    /** Intervalo de muestreo en minutos (regla #3 del spec: default 5). */
    val pollIntervalMinutes: Int = 5,
    /** Retención de particiones de interface_metrics en días (regla #5: default 90). */
    val retentionDays: Int = 90,
)
