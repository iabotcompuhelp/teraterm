package com.opentermx.mcp.handlers

import com.opentermx.mcp.adapters.EffectiveCapabilitiesService
import com.opentermx.mcp.fingerprint.DeviceProfileViews
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.UNAVAILABLE
import com.opentermx.mcp.telemetry.TelemetryStore
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions

/**
 * `get_management_methods` (Fase 6C.1, read-only): expone la intersección modelo ∩
 * dispositivo ∩ flag ∩ runtime calculada por [EffectiveCapabilitiesService]. El LLM la
 * consulta para saber qué puede hacer con un equipo; las escrituras siguen requiriendo
 * aprobación humana aparte.
 */
class GetManagementMethodsHandler(
    private val store: TelemetryStore,
    private val views: DeviceProfileViews,
    private val capabilities: EffectiveCapabilitiesService,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.GET_MANAGEMENT_METHODS

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val hostname = Args.requireString(args, "deviceHostname")
        store.db() ?: throw McpToolException(
            UNAVAILABLE,
            "DB_UNAVAILABLE: la base de telemetría no está configurada o no responde.",
        )
        val deviceId = views.resolveDeviceId(hostname)
            ?: return linkedMapOf("found" to false, "deviceHostname" to hostname)

        val statuses = capabilities.statusFor(deviceId)
        return linkedMapOf(
            "found" to true,
            "deviceHostname" to hostname,
            "effectiveMethods" to statuses.filter { it.effective }.map { it.method.name },
            "methods" to statuses.map { s ->
                linkedMapOf(
                    "method" to s.method.name,
                    "effective" to s.effective,
                    "supportedByCatalog" to s.supportedByCatalog,
                    "enabledOnDevice" to s.enabledOnDevice,
                    "flagEnabled" to s.flagEnabled,
                    "availableInRuntime" to s.availableInRuntime,
                    "unavailableReason" to s.unavailableReason,
                    "readOperations" to s.readOperations,
                )
            },
        )
    }
}
