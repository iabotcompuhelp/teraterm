package com.opentermx.mcp.handlers

import com.fasterxml.jackson.databind.ObjectMapper
import com.opentermx.integrations.IntegrationException
import com.opentermx.integrations.IntegrationKind
import com.opentermx.integrations.IntegrationRegistry
import com.opentermx.integrations.MonitoringIntegration
import com.opentermx.integrations.OpManagerClient
import com.opentermx.integrations.ZabbixClient
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.INVALID_ARGUMENT
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.NOT_FOUND
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.UNAVAILABLE
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tools READ-ONLY contra plataformas de monitoreo (Fase 4): Zabbix y OpManager.
 *
 * Mitigación de prompt injection indirecta (regla del spec): todo lo que viene de la
 * plataforma externa (nombres de triggers, mensajes de alarmas) viaja dentro del campo
 * `data`, y la respuesta lleva `contentOrigin: "external_monitoring_platform"` para que
 * el cliente MCP lo trate como NO confiable. Además, si el `data` serializado supera
 * 64 KB se trunca (error #26) recortando las listas más largas, con `truncated: true`.
 */
internal object MonitoringSupport {

    private val mapper = ObjectMapper()
    private val zabbixClients = ConcurrentHashMap<String, Pair<String, ZabbixClient>>()
    private val opManagerClients = ConcurrentHashMap<String, Pair<String, OpManagerClient>>()

    const val MAX_DATA_BYTES = 64 * 1024
    const val CONTENT_ORIGIN = "external_monitoring_platform"

    fun resolve(registry: IntegrationRegistry, name: String, expected: IntegrationKind): MonitoringIntegration {
        val integration = registry.byName(name)
            ?: throw McpToolException(
                NOT_FOUND,
                "Integración `$name` no configurada. Definila en los settings de OpenTermX " +
                    "(sección `monitoringIntegrations`).",
            )
        if (integration.kind != expected) {
            throw McpToolException(
                INVALID_ARGUMENT,
                "La integración `$name` es ${integration.kind}, esta tool requiere $expected",
            )
        }
        return integration
    }

    /** Clientes cacheados (conservan modo de auth/sesión); se rearman si cambió la config. */
    fun zabbix(integration: MonitoringIntegration): ZabbixClient {
        val fingerprint = fingerprintOf(integration)
        val cached = zabbixClients[integration.name]
        if (cached != null && cached.first == fingerprint) return cached.second
        return ZabbixClient(integration).also { zabbixClients[integration.name] = fingerprint to it }
    }

    fun opManager(integration: MonitoringIntegration): OpManagerClient {
        val fingerprint = fingerprintOf(integration)
        val cached = opManagerClients[integration.name]
        if (cached != null && cached.first == fingerprint) return cached.second
        return OpManagerClient(integration).also { opManagerClients[integration.name] = fingerprint to it }
    }

    private fun fingerprintOf(i: MonitoringIntegration): String =
        "${i.kind}|${i.baseUrl}|${i.verifyTls}|${i.extra}"

    /**
     * Envuelve datos externos con el marcado de origen + truncado server-side. Las
     * llamadas bloqueantes de los clientes corren en IO — nunca en el hilo del server.
     */
    suspend fun <T> wrap(integrationName: String, block: () -> T): Map<String, Any?> {
        val data = try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: IntegrationException) {
            throw McpToolException(UNAVAILABLE, e.message ?: "Error de la integración")
        }
        val (finalData, truncated) = truncateIfHuge(data)
        return linkedMapOf(
            "integration" to integrationName,
            "contentOrigin" to CONTENT_ORIGIN,
            "truncated" to truncated,
            "data" to finalData,
        )
    }

    /**
     * Si el payload serializado supera [MAX_DATA_BYTES], recorta a la mitad la lista
     * más larga que encuentre (hasta 12 pasadas) e indica `truncated`. El cliente debe
     * afinar el filtro — mandar medio mega al contexto del LLM no ayuda a nadie.
     */
    private fun truncateIfHuge(data: Any?): Pair<Any?, Boolean> {
        var current = data
        var truncated = false
        repeat(12) {
            val size = runCatching { mapper.writeValueAsBytes(current).size }.getOrDefault(0)
            if (size <= MAX_DATA_BYTES) return current to truncated
            current = halveLongestList(current)
            truncated = true
        }
        return current to truncated
    }

    @Suppress("UNCHECKED_CAST")
    private fun halveLongestList(data: Any?): Any? = when (data) {
        is List<*> -> data.take((data.size / 2).coerceAtLeast(1))
        is Map<*, *> -> {
            val m = data as Map<String, Any?>
            val longestKey = m.entries
                .filter { it.value is List<*> }
                .maxByOrNull { (it.value as List<*>).size }
                ?.key
            if (longestKey == null) m
            else m.mapValues { (k, v) ->
                if (k == longestKey) (v as List<*>).take(((v.size) / 2).coerceAtLeast(1)) else v
            }
        }
        else -> data
    }
}

/** `zabbix_get_history` — histórico de métricas desde Zabbix (read-only). */
class ZabbixGetHistoryHandler(
    private val registry: () -> IntegrationRegistry,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.ZABBIX_GET_HISTORY

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val integrationName = Args.requireString(args, "integrationName")
        val hostName = Args.requireString(args, "hostName")
        val itemKeyPattern = Args.requireString(args, "itemKeyPattern")
        val fromIso = Args.requireString(args, "fromIso")
        val toIso = Args.optionalString(args, "toIso")
        val limit = Args.optionalInt(args, "limit", default = 1000, min = 1, max = 5000)

        val integration = MonitoringSupport.resolve(registry(), integrationName, IntegrationKind.ZABBIX)
        return MonitoringSupport.wrap(integrationName) {
            MonitoringSupport.zabbix(integration).getHistory(hostName, itemKeyPattern, fromIso, toIso, limit)
        }
    }
}

/** `zabbix_get_active_problems` — problemas activos en Zabbix (read-only). */
class ZabbixGetActiveProblemsHandler(
    private val registry: () -> IntegrationRegistry,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.ZABBIX_GET_ACTIVE_PROBLEMS

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val integrationName = Args.requireString(args, "integrationName")
        val hostName = Args.optionalString(args, "hostName")
        val minSeverity = Args.optionalInt(args, "minSeverity", default = 0, min = 0, max = 5)
        val limit = Args.optionalInt(args, "limit", default = 100, min = 1, max = 500)

        val integration = MonitoringSupport.resolve(registry(), integrationName, IntegrationKind.ZABBIX)
        return MonitoringSupport.wrap(integrationName) {
            mapOf("problems" to MonitoringSupport.zabbix(integration).getActiveProblems(hostName, minSeverity, limit))
        }
    }
}

/** `opmanager_get_alarms` — alarmas desde OpManager (read-only, mapeo tolerante). */
class OpManagerGetAlarmsHandler(
    private val registry: () -> IntegrationRegistry,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.OPMANAGER_GET_ALARMS

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val integrationName = Args.requireString(args, "integrationName")
        val deviceName = Args.optionalString(args, "deviceName")
        val severity = Args.optionalString(args, "severity")
        val limit = Args.optionalInt(args, "limit", default = 100, min = 1, max = 500)

        val integration = MonitoringSupport.resolve(registry(), integrationName, IntegrationKind.OPMANAGER)
        return MonitoringSupport.wrap(integrationName) {
            MonitoringSupport.opManager(integration).listAlarms(deviceName, severity, limit)
        }
    }
}

/** `opmanager_get_performance` — performance histórica desde OpManager (read-only). */
class OpManagerGetPerformanceHandler(
    private val registry: () -> IntegrationRegistry,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.OPMANAGER_GET_PERFORMANCE

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val integrationName = Args.requireString(args, "integrationName")
        val deviceName = Args.requireString(args, "deviceName")
        val monitorName = Args.optionalString(args, "monitorName")
        val period = Args.optionalString(args, "period") ?: "last7days"
        val fromIso = Args.optionalString(args, "fromIso")
        val toIso = Args.optionalString(args, "toIso")
        if (period !in setOf("today", "yesterday", "last7days", "last30days", "custom")) {
            throw McpToolException(INVALID_ARGUMENT, "`period` inválido: $period")
        }

        val integration = MonitoringSupport.resolve(registry(), integrationName, IntegrationKind.OPMANAGER)
        return MonitoringSupport.wrap(integrationName) {
            MonitoringSupport.opManager(integration).getPerformance(deviceName, monitorName, period, fromIso, toIso)
        }
    }
}
