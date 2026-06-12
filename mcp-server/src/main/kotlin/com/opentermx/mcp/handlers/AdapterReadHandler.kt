package com.opentermx.mcp.handlers

import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.adapters.EffectiveCapabilitiesService
import com.opentermx.mcp.fingerprint.DeviceProfileViews
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.INVALID_ARGUMENT
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.UNAVAILABLE
import com.opentermx.mcp.telemetry.TelemetryStore
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions
import com.opentermx.mgmt.AdapterRegistry
import com.opentermx.mgmt.AdapterResult
import com.opentermx.mgmt.DeviceRef
import com.opentermx.mgmt.MgmtMethod
import com.opentermx.mgmt.OperationKind
import com.opentermx.mgmt.ReadOperation
import com.opentermx.netparsers.Vendor

/**
 * `adapter_read` (Fase 6C.2): ejecuta una operación de SOLO LECTURA vía un adaptador
 * habilitado. Acá nace la **validación server-side de read-only (error #56)** que heredan
 * todos los adaptadores: la operación pedida debe estar en el descriptor del adaptador y
 * ser [OperationKind.READ]. Nunca se confía en que el LLM eligió la tool correcta.
 *
 * Orden de chequeos (fail-closed): BD disponible → device existe → método EFECTIVO
 * (intersección modelo/dispositivo/flag/runtime) → operación de lectura del descriptor →
 * ejecutar. El contenido devuelto es de una plataforma externa: `contentOrigin` lo marca.
 */
class AdapterReadHandler(
    private val store: TelemetryStore,
    private val views: DeviceProfileViews,
    private val capabilities: EffectiveCapabilitiesService,
    private val registry: AdapterRegistry,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.ADAPTER_READ

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val hostname = Args.requireString(args, "deviceHostname")
        val methodRaw = Args.requireString(args, "method")
        val operation = Args.requireString(args, "operation")
        @Suppress("UNCHECKED_CAST")
        val params = (args["params"] as? Map<String, Any?>) ?: emptyMap()

        val db = store.db() ?: throw McpToolException(
            UNAVAILABLE, "DB_UNAVAILABLE: la base de telemetría no está configurada o no responde.",
        )
        val method = runCatching { MgmtMethod.valueOf(methodRaw) }.getOrNull()
            ?: throw McpToolException(INVALID_ARGUMENT, "método desconocido: `$methodRaw`")
        val deviceId = views.resolveDeviceId(hostname)
            ?: throw McpToolException(McpToolException.ErrorCode.NOT_FOUND, "device `$hostname` no inventariado")

        // Método EFECTIVO: la intersección decide; si no, el motivo exacto.
        val status = capabilities.statusFor(deviceId).firstOrNull { it.method == method }
        if (status == null || !status.effective) {
            val reason = status?.let {
                buildString {
                    if (!it.supportedByCatalog) append("no soportado por el modelo; ")
                    if (!it.enabledOnDevice) append("no habilitado en el dispositivo (opt-in); ")
                    if (!it.flagEnabled) append("feature flag apagado; ")
                    if (!it.availableInRuntime) append(it.unavailableReason ?: "adaptador no disponible; ")
                }.trim().trimEnd(';')
            } ?: "método no disponible"
            throw McpToolException(INVALID_ARGUMENT, "método `$method` no efectivo para `$hostname`: $reason")
        }
        val adapter = registry.forMethod(method)
            ?: throw McpToolException(UNAVAILABLE, "sin adaptador `$method` en runtime")

        val device = db.devices.findById(deviceId)!!
        val ref = DeviceRef(
            deviceId = deviceId,
            hostname = device["hostname"]?.toString() ?: hostname,
            vendor = runCatching { Vendor.valueOf(device["vendor"].toString()) }.getOrDefault(Vendor.UNKNOWN),
            family = db.catalog.catalogModelOf(deviceId)?.family,
            sessionId = SessionRegistry.metadataOf(SessionId(hostname))?.let { hostname },
        )

        // Validación #56: la operación debe ser de LECTURA del descriptor de ESTE adaptador.
        val descriptor = adapter.describe(ref)
        val op = descriptor.operation(operation)
        if (op == null || op.kind != OperationKind.READ) {
            throw McpToolException(
                INVALID_ARGUMENT,
                if (op == null) "operación `$operation` no existe para `$method`"
                else "operación `$operation` no es de lectura (es ${op.kind}); usá propose_adapter_write",
            )
        }

        return when (val result = adapter.executeRead(ref, ReadOperation(operation, params))) {
            is AdapterResult.Success -> linkedMapOf(
                "ok" to true,
                "method" to method.name,
                "operation" to operation,
                "contentOrigin" to "external_device",
                "data" to result.data,
            )
            is AdapterResult.Failure -> linkedMapOf(
                "ok" to false,
                "method" to method.name,
                "operation" to operation,
                "contentOrigin" to "external_device",
                "error" to result.reason,
            )
        }
    }
}
