package com.opentermx.mcp.handlers

import com.opentermx.ai.audit.AiAuditEntry
import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.ai.context.Vendor
import com.opentermx.ai.context.VendorDetector
import com.opentermx.ai.safety.ClassifiedCommand
import com.opentermx.ai.safety.CredentialRedactor
import com.opentermx.ai.safety.RiskLevel
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.INVALID_ARGUMENT
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.NOT_FOUND
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.UNAVAILABLE
import com.opentermx.mcp.security.ApprovalDecision
import com.opentermx.mcp.security.ApprovalGate
import com.opentermx.mcp.security.ReadOnlyCommandValidator
import com.opentermx.mcp.security.ReadOnlyValidation
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions
import java.util.UUID

/**
 * Handler de la tool `run_readonly_command`: ejecuta UN comando de solo lectura
 * (`show …`, `display …`, `get …`, ping/traceroute) contra una sesión activa.
 *
 * Diferencia con `propose_commands`: acá el gate humano es OPCIONAL. La invariante de
 * seguridad la sostiene el [ReadOnlyCommandValidator] — whitelist estricta por vendor,
 * sin metacaracteres, sin pipes a comandos de escritura — que corre SIEMPRE, antes de
 * cualquier aprobación. Un comando que no pase la whitelist se rechaza con
 * INVALID_ARGUMENT y la respuesta dirige al cliente a `propose_commands` (gate
 * obligatorio para todo lo mutativo).
 *
 * Modos:
 *  - `autoApprove() == false` (default): el operador igualmente ve el diálogo de
 *    aprobación, con el comando ya pre-clasificado SAFE. Si el operador EDITA el
 *    comando en el diálogo, la edición se re-valida contra la whitelist; si la edición
 *    introduce algo no read-only, no se ejecuta NADA (fail-closed) — el camino correcto
 *    para eso es `propose_commands`.
 *  - `autoApprove() == true`: se ejecuta sin diálogo. Pensado para monitoreo/diagnóstico
 *    desatendido; se habilita con el setting `mcpServerReadonlyAutoApprove`.
 *
 * Todo lo ejecutado (o rechazado por el gate) queda en el [AiAuditLog], igual que
 * `propose_commands`.
 */
class RunReadonlyCommandHandler(
    private val approvalGate: ApprovalGate,
    /** Lambda (no boolean) para que el toggle del setting aplique sin reiniciar el server. */
    private val autoApprove: () -> Boolean = { false },
    private val auditLog: AiAuditLog = AiAuditLog(),
    private val redactor: CredentialRedactor = CredentialRedactor(),
    /** Espera tras inyectar antes de leer el buffer — darle tiempo al device a responder. */
    private val outputWaitMillis: Long = DEFAULT_OUTPUT_WAIT_MILLIS,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.RUN_READONLY_COMMAND

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val sessionIdRaw = Args.requireString(args, "sessionId")
        val command = Args.requireString(args, "command")
        val lastLines = Args.optionalInt(
            args, "lastLines",
            default = ToolDefinitions.DEFAULT_LAST_LINES,
            min = 1, max = ToolDefinitions.MAX_LAST_LINES,
        )
        val rationale = Args.optionalString(args, "rationale").orEmpty()

        val sessionId = SessionId(sessionIdRaw)
        val metadata = SessionRegistry.metadataOf(sessionId)
            ?: throw McpToolException(NOT_FOUND, "Sesión `$sessionIdRaw` no encontrada en el registro")
        val sink = SessionRegistry.sinkOf(sessionId)
            ?: throw McpToolException(UNAVAILABLE, "Sesión `$sessionIdRaw` sin sink: no es inyectable")

        val sample = SessionRegistry.lastLinesOf(sessionId, VENDOR_SAMPLE_LINES).joinToString("\n")
        val vendor = if (sample.isBlank()) Vendor.UNKNOWN else VendorDetector.detect(sample)

        // La whitelist corre SIEMPRE, incluso con auto-approve activo: es la invariante
        // de seguridad de esta tool.
        rejectIfNotReadonly(command, vendor)

        val auditLogId = UUID.randomUUID().toString()
        val autoApproved = autoApprove()
        val commandsToRun: List<String> = if (autoApproved) {
            listOf(command)
        } else {
            val decision = approvalGate.reviewCommands(
                rationale.ifBlank { "(mcp run_readonly_command)" },
                vendor,
                listOf(ClassifiedCommand(command, RiskLevel.SAFE)),
            )
            when (decision) {
                is ApprovalDecision.Reject -> {
                    logAudit(auditLogId, sessionId, metadata.host, vendor, rationale,
                        listOf(command), executed = 0, rejected = true, outputTail = "")
                    return linkedMapOf(
                        "approved" to false,
                        "autoApproved" to false,
                        "executed" to 0,
                        "vendor" to vendor.displayName,
                        "auditLogId" to auditLogId,
                        "output" to null,
                    )
                }
                is ApprovalDecision.Approve -> {
                    // Si el operador editó en el diálogo, la edición debe seguir siendo
                    // read-only. Fail-closed: una sola línea inválida aborta todo.
                    decision.commands.forEach { rejectIfNotReadonly(it, vendor) }
                    decision.commands
                }
            }
        }

        var executed = 0
        var failed = 0
        for (cmd in commandsToRun) {
            val ok = runCatching { sink.sendLine(cmd) }.getOrDefault(false)
            if (ok) executed++ else failed++
        }
        if (outputWaitMillis > 0) {
            kotlinx.coroutines.delay(outputWaitMillis)
        }
        val tailRedacted = redactor.redact(
            SessionRegistry.lastLinesOf(sessionId, lastLines).joinToString("\n"),
            vendor,
        )
        logAudit(auditLogId, sessionId, metadata.host, vendor, rationale,
            commandsToRun, executed = executed, failed = failed, rejected = false, outputTail = tailRedacted)
        return linkedMapOf(
            "approved" to true,
            "autoApproved" to autoApproved,
            "executed" to executed,
            "vendor" to vendor.displayName,
            "auditLogId" to auditLogId,
            "output" to tailRedacted,
        )
    }

    private fun rejectIfNotReadonly(command: String, vendor: Vendor) {
        val verdict = ReadOnlyCommandValidator.validate(command, vendor)
        if (verdict is ReadOnlyValidation.Rejected) {
            throw McpToolException(INVALID_ARGUMENT, "Comando rechazado por la whitelist read-only: ${verdict.reason}")
        }
    }

    private fun logAudit(
        auditLogId: String,
        sessionId: SessionId,
        host: String?,
        vendor: Vendor,
        rationale: String,
        commands: List<String>,
        executed: Int,
        failed: Int = 0,
        rejected: Boolean,
        outputTail: String,
    ) {
        auditLog.append(
            AiAuditEntry(
                timestampMillis = System.currentTimeMillis(),
                sessionId = "${sessionId.value}#$auditLogId",
                host = host,
                vendor = vendor.takeIf { it != Vendor.UNKNOWN }?.displayName,
                prompt = rationale.ifBlank { "(mcp run_readonly_command)" },
                commands = commands,
                commandRisks = commands.map { RiskLevel.SAFE },
                executedCount = executed,
                skippedCount = (commands.size - executed - failed).coerceAtLeast(0),
                failedCount = failed,
                rejected = rejected,
                outputTail = outputTail,
            )
        )
    }

    companion object {
        const val DEFAULT_OUTPUT_WAIT_MILLIS = 800L
        private const val VENDOR_SAMPLE_LINES = 64
    }
}
