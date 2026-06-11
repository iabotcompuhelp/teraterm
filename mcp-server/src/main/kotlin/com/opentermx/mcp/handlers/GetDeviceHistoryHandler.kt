package com.opentermx.mcp.handlers

import com.opentermx.mcp.handlers.McpToolException.ErrorCode.INVALID_ARGUMENT
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.NOT_FOUND
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.UNAVAILABLE
import com.opentermx.mcp.telemetry.TelemetryStore
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions
import java.time.OffsetDateTime

/**
 * `get_device_history` (Fase 3): consulta el histórico local en PostgreSQL. A diferencia
 * de las tools de telemetría en vivo (que degradan con gracia sin BD), acá la BD ES el
 * dato — sin ella se responde el error claro `DB_UNAVAILABLE` que pide el spec.
 */
class GetDeviceHistoryHandler(
    private val store: TelemetryStore,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.GET_DEVICE_HISTORY

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val hostname = Args.requireString(args, "deviceHostname")
        val dataType = Args.requireString(args, "dataType")
        val interfaceName = Args.optionalString(args, "interfaceName")
        val from = parseIso(Args.optionalString(args, "fromIso"), "fromIso")
        val to = parseIso(Args.optionalString(args, "toIso"), "toIso")
        val limit = Args.optionalInt(args, "limit", default = 200, min = 1, max = 1000)

        val db = store.db()
        if (db == null || !store.isAvailable()) {
            throw McpToolException(
                UNAVAILABLE,
                "DB_UNAVAILABLE: PostgreSQL no está configurado o no responde. " +
                    "Configurá la sección `database` en los settings de OpenTermX.",
            )
        }
        if (db.devices.findIdByHostname(hostname) == null) {
            throw McpToolException(NOT_FOUND, "Device `$hostname` sin registros en la BD de telemetría")
        }

        val rows = when (dataType) {
            "interface_metrics" -> db.history.interfaceMetrics(hostname, interfaceName, from, to, limit)
            "link_events" -> db.history.linkEvents(hostname, interfaceName, from, to, limit)
            "config_diffs" -> db.history.configDiffs(hostname, from, to, limit)
            "command_audit" -> db.history.commandAudit(hostname, from, to, limit)
            else -> throw McpToolException(
                INVALID_ARGUMENT,
                "`dataType` debe ser interface_metrics | link_events | config_diffs | command_audit",
            )
        }
        return linkedMapOf(
            "deviceHostname" to hostname,
            "dataType" to dataType,
            "count" to rows.size,
            "rows" to rows,
        )
    }

    private fun parseIso(value: String?, field: String): OffsetDateTime? {
        if (value == null) return null
        return runCatching { OffsetDateTime.parse(value) }.getOrElse {
            throw McpToolException(INVALID_ARGUMENT, "`$field` debe ser ISO-8601 con zona (ej. 2026-06-01T00:00:00Z)")
        }
    }
}
