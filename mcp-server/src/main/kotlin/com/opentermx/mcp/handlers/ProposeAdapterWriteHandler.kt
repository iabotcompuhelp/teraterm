package com.opentermx.mcp.handlers

import com.opentermx.ai.audit.AiAuditEntry
import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.ai.safety.ClassifiedCommand
import com.opentermx.ai.safety.RiskLevel
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.adapters.EffectiveCapabilitiesService
import com.opentermx.mcp.fingerprint.DeviceProfileViews
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.INVALID_ARGUMENT
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.UNAVAILABLE
import com.opentermx.mcp.security.ApprovalDecision
import com.opentermx.mcp.security.ApprovalGate
import com.opentermx.mcp.telemetry.TelemetryStore
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions
import com.opentermx.mgmt.AdapterRegistry
import com.opentermx.mgmt.AdapterResult
import com.opentermx.mgmt.DeviceRef
import com.opentermx.mgmt.MgmtMethod
import com.opentermx.mgmt.OperationKind
import com.opentermx.mgmt.WriteOperation
import java.util.UUID
import com.opentermx.ai.context.Vendor as AiVendor
import com.opentermx.netparsers.Vendor as NetVendor

/**
 * `propose_adapter_write` (Fase 6C.3): PROPONE un cambio de configuración vía un adaptador
 * de gestión (REST_API) y lo somete a la aprobación humana del [ApprovalGate]. Cierra la
 * rama de escritura del módulo, simétrica a `adapter_read` (6C.2).
 *
 * Regla de diseño central de OpenTermX: ningún cambio se aplica sin que un operador lo
 * apruebe explícitamente viendo antes el detalle exacto. Por eso el flujo es:
 *
 *  1. Feature flag `adapters.rest.write.enabled`: si está apagado, responde `disabled` y
 *     NO crea ticket (la aplicación de cambios no se expone todavía).
 *  2. Validación server-side: device inventariado, método EFECTIVO (intersección
 *     modelo/dispositivo/flag/runtime) y operación de ESCRITURA del descriptor. Una
 *     operación de lectura enviada acá se rechaza: las lecturas van por `adapter_read`.
 *  3. `proposeWrite` construye el ticket con el payload literal — NO toca el equipo.
 *  4. El ticket va al [ApprovalGate], que muestra la representación literal al operador.
 *  5. Sólo si el operador aprueba, se invoca `applyApprovedChange` (la única ruta que toca
 *     el equipo). Si rechaza, no se aplica nada y queda auditado como rechazado.
 *
 * INVARIANTE: `applyApprovedChange` se invoca EXCLUSIVAMENTE en la rama [ApprovalDecision.Approve]
 * de este handler. No hay ninguna otra ruta hacia él (ver KDoc en `ManagementAdapter`).
 */
class ProposeAdapterWriteHandler(
    private val store: TelemetryStore,
    private val views: DeviceProfileViews,
    private val capabilities: EffectiveCapabilitiesService,
    private val registry: AdapterRegistry,
    private val approvalGate: ApprovalGate,
    /** Flag `adapters.rest.write.enabled`, leído en vivo (togglear no exige reiniciar). */
    private val writeEnabled: () -> Boolean,
    private val auditLog: AiAuditLog = AiAuditLog(),
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.PROPOSE_ADAPTER_WRITE

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val hostname = Args.requireString(args, "deviceHostname")
        val methodRaw = Args.requireString(args, "method")
        val operation = Args.requireString(args, "operation")
        val rationale = Args.requireString(args, "rationale")
        @Suppress("UNCHECKED_CAST")
        val payload = (args["payload"] as? Map<String, Any?>) ?: emptyMap()

        // 1) Feature flag: apagado → deshabilitado, sin ticket y sin tocar la BD/gate.
        if (!writeEnabled()) {
            return linkedMapOf(
                "ok" to false,
                "status" to "disabled",
                "ticketId" to null,
                "method" to methodRaw,
                "operation" to operation,
                "error" to "La aplicación de cambios vía adaptador está deshabilitada " +
                    "(`adapters.rest.write.enabled=false`). Habilitala para proponer escrituras.",
            )
        }

        // 2) Validación server-side (mismo orden fail-closed que adapter_read).
        val db = store.db() ?: throw McpToolException(
            UNAVAILABLE, "DB_UNAVAILABLE: la base de telemetría no está configurada o no responde.",
        )
        val method = runCatching { MgmtMethod.valueOf(methodRaw) }.getOrNull()
            ?: throw McpToolException(INVALID_ARGUMENT, "método desconocido: `$methodRaw`")
        val deviceId = views.resolveDeviceId(hostname)
            ?: throw McpToolException(McpToolException.ErrorCode.NOT_FOUND, "device `$hostname` no inventariado")

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
            vendor = runCatching { NetVendor.valueOf(device["vendor"].toString()) }.getOrDefault(NetVendor.UNKNOWN),
            family = db.catalog.catalogModelOf(deviceId)?.family,
            sessionId = SessionRegistry.metadataOf(SessionId(hostname))?.let { hostname },
        )

        // La operación DEBE ser de ESCRITURA del descriptor de ESTE adaptador. Una lectura
        // enviada acá se rechaza: su lugar es `adapter_read` (responsabilidad separada).
        val descriptor = adapter.describe(ref)
        val op = descriptor.operation(operation)
        if (op == null || op.kind != OperationKind.WRITE) {
            throw McpToolException(
                INVALID_ARGUMENT,
                if (op == null) "operación `$operation` no existe para `$method`"
                else "operación `$operation` es de lectura (${op.kind}); usá adapter_read, no propose_adapter_write",
            )
        }

        // 3) Construir el ticket (NO toca el equipo).
        val writeOp = WriteOperation(operation, payload, rationale)
        val ticket = adapter.proposeWrite(ref, writeOp)
        val ticketId = UUID.randomUUID().toString()
        val auditLogId = UUID.randomUUID().toString()

        // 4) Aprobación humana: el operador ve la representación literal del cambio.
        val classifications = classifyTicket(operation, ticket.literalPayload)
        val gateVendor = aiVendorOf(ref.vendor)
        val decision = approvalGate.reviewCommands(rationale, gateVendor, classifications)

        return when (decision) {
            is ApprovalDecision.Reject -> {
                logAudit(auditLogId, hostname, method, operation, classifications, rejected = true, applied = false, detail = "rechazado por el operador")
                linkedMapOf(
                    "ok" to false,
                    "status" to "rejected",
                    "ticketId" to ticketId,
                    "method" to method.name,
                    "operation" to operation,
                    "literalPayload" to ticket.literalPayload,
                    "auditLogId" to auditLogId,
                    "error" to null,
                )
            }
            is ApprovalDecision.Approve -> {
                // ÚNICA ruta que toca el equipo, y sólo tras la aprobación humana.
                // TODO(6C.3): snapshot pre/post del cambio aprobado. Reutilizar la captura
                // de la Fase 3 cuando haya sesión CLI sobre el device; para devices sólo-REST
                // (sin running-config por CLI) el hook queda marcado hasta su propia entrega.
                val result = adapter.applyApprovedChange(ref, writeOp)
                val applied = result is AdapterResult.Success
                val detail = when (result) {
                    is AdapterResult.Success -> "aplicado"
                    is AdapterResult.Failure -> "aprobado, aplicación falló: ${result.reason}"
                }
                logAudit(auditLogId, hostname, method, operation, classifications, rejected = false, applied = applied, detail = detail)
                linkedMapOf(
                    "ok" to applied,
                    "status" to if (applied) "applied" else "apply_failed",
                    "ticketId" to ticketId,
                    "method" to method.name,
                    "operation" to operation,
                    "literalPayload" to ticket.literalPayload,
                    "auditLogId" to auditLogId,
                    "error" to (result as? AdapterResult.Failure)?.reason,
                )
            }
        }
    }

    /**
     * Representa el cambio propuesto como líneas clasificadas para el panel de aprobación:
     * una cabecera con el id de la operación + el payload literal. El operador ve el detalle
     * exacto antes de decidir. Toda línea de un cambio de configuración es al menos CONFIG.
     */
    private fun classifyTicket(operation: String, literalPayload: String): List<ClassifiedCommand> {
        val header = ClassifiedCommand("# operación de escritura: $operation", RiskLevel.CONFIG)
        val payloadLines = literalPayload.split('\n').filter { it.isNotBlank() }.map {
            ClassifiedCommand(it, RiskLevel.CONFIG)
        }
        return listOf(header) + payloadLines
    }

    private fun aiVendorOf(vendor: NetVendor): AiVendor =
        runCatching { AiVendor.valueOf(vendor.name) }.getOrDefault(AiVendor.UNKNOWN)

    private fun logAudit(
        auditLogId: String,
        host: String,
        method: MgmtMethod,
        operation: String,
        classifications: List<ClassifiedCommand>,
        rejected: Boolean,
        applied: Boolean,
        detail: String,
    ) {
        auditLog.append(
            AiAuditEntry(
                timestampMillis = System.currentTimeMillis(),
                sessionId = "$host#$auditLogId",
                host = host,
                vendor = null,
                prompt = "(mcp propose_adapter_write: ${method.name}/$operation — $detail)",
                commands = classifications.map { it.raw },
                commandRisks = classifications.map { it.risk },
                executedCount = if (applied) 1 else 0,
                skippedCount = 0,
                failedCount = if (!rejected && !applied) 1 else 0,
                rejected = rejected,
                outputTail = "",
            )
        )
    }
}
