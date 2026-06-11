package com.opentermx.app.settings

import com.fasterxml.jackson.annotation.JsonInclude
import com.opentermx.common.crypto.EncryptedValue

/**
 * Una integración de monitoreo externa (Fase 4): Zabbix u OpManager. Se configura en
 * `~/.opentermx/settings.json` → `monitoringIntegrations`.
 *
 * El token/apiKey NUNCA en texto plano: [token] cifrado con SecretCipher, o si está
 * vacío, la variable de entorno `OPENTERMX_INTEGRATION_<NOMBRE>_TOKEN` (nombre en
 * mayúsculas, no-alfanuméricos → `_`). Para Zabbix legacy (5.x–6.0) el secreto puede
 * ser `usuario:password`.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MonitoringIntegrationSetting(
    /** ZABBIX | OPMANAGER */
    val kind: String = "ZABBIX",
    val name: String = "",
    val baseUrl: String = "",
    val verifyTls: Boolean = true,
    /** Override del modo de auth de Zabbix (p. ej. "6.0" fuerza legacy, "7.0" Bearer). */
    val apiVersionOverride: String? = null,
    val token: EncryptedValue? = null,
)
