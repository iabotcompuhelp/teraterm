package com.opentermx.app.settings

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Configuración persistida de la REST API embebida (spec v4 § API REST interna).
 *
 * - `enabled` arranca el servidor en cuanto se aplica la configuración (o al boot).
 * - `bindHost` por defecto `127.0.0.1` para evitar exposición accidental. Para integrar
 *   con n8n/SIEM remotos cambiar a `0.0.0.0` y configurar firewall.
 * - `token` se genera la primera vez que se habilita; el usuario lo copia y pega en sus
 *   integraciones externas.
 * - `apiLogPath` vacío = sin auditoría; ruta absoluta = CSV append-only en disco.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RestApiPersistedSettings(
    val enabled: Boolean = false,
    val bindHost: String = "127.0.0.1",
    val port: Int = 8080,
    val token: String = "",
    val requireAuth: Boolean = true,
    val apiLogPath: String = "",
)
