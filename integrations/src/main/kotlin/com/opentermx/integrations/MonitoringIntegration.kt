package com.opentermx.integrations

/** Plataforma de monitoreo soportada. Alineado con el enum `integration_t` de la BD. */
enum class IntegrationKind { ZABBIX, OPMANAGER }

/**
 * Una integración configurada. El secreto (API token de Zabbix, apiKey de OpManager,
 * o `usuario:password` para Zabbix legacy) llega por [secretProvider] — se resuelve
 * recién al usarlo y NUNCA se loguea, se serializa ni viaja en mensajes de error
 * (ver [HttpSupport.scrub]).
 */
data class MonitoringIntegration(
    val kind: IntegrationKind,
    val name: String,
    val baseUrl: String,
    val verifyTls: Boolean = true,
    /** Overrides por plataforma — p. ej. `apiVersion` para forzar el modo de auth de Zabbix. */
    val extra: Map<String, String> = emptyMap(),
    val secretProvider: () -> String,
)

/** Resolución de integraciones por nombre. La app lo implementa desde sus settings. */
fun interface IntegrationRegistry {
    fun byName(name: String): MonitoringIntegration?

    companion object {
        val Empty = IntegrationRegistry { null }
    }
}

/**
 * Error de integración apto para el cliente MCP: el mensaje ya viene depurado de
 * secretos y sin stacktraces de transporte.
 */
class IntegrationException(message: String) : RuntimeException(message)
