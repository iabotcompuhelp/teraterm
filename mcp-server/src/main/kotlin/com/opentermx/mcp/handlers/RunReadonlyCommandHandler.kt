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
import com.opentermx.mcp.exec.SessionCommandRunner
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
 * Handler de la tool `run_readonly_command` (Fase 1 del plan de telemetría): ejecuta UN
 * comando de solo lectura contra una sesión activa con detección de prompt, manejo de
 * paginador y timeout, devolviendo el output limpio.
 *
 * La invariante de seguridad la sostiene el [ReadOnlyCommandValidator] — whitelist regex
 * por vendor (YAML editable), sin metacaracteres, sin pipes a comandos de escritura — que
 * corre SIEMPRE antes de tocar la sesión. Vendor no detectado => rechazo explícito, no se
 * adivina (error #8 del catálogo).
 *
 * Aprobación: por spec esta tool NO requiere gate humano (su razón de ser es que el LLM
 * consulte estado de forma autónoma). El setting `mcpServerReadonlyAutoApprove`
 * (checkbox "Allow read-only commands without approval", default ON) permite volver al
 * gate: con el setting OFF, cada comando abre el diálogo de aprobación. Si el operador
 * edita el comando en el diálogo, la edición se re-valida contra la whitelist —
 * fail-closed: edición no read-only aborta sin ejecutar nada.
 *
 * Toda invocación queda en el [AiAuditLog], incluso los rechazos del validador (el
 * intento de colar `configure terminal` por acá ES información de auditoría). El flag
 * `read_only=true` como columna llega con la tabla `command_audit` de la Fase 3; mientras
 * tanto el marcador es el prompt `(mcp run_readonly_command)`.
 */
class RunReadonlyCommandHandler(
    private val approvalGate: ApprovalGate,
    /** Lambda (no boolean) para que el toggle del setting aplique sin reiniciar el server. */
    private val allowWithoutApproval: () -> Boolean = { true },
    private val auditLog: AiAuditLog = AiAuditLog(),
    private val redactor: CredentialRedactor = CredentialRedactor(),
    private val runner: SessionCommandRunner = SessionCommandRunner(),
    private val validatorProvider: () -> ReadOnlyCommandValidator = cachedDefaultValidator(),
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.RUN_READONLY_COMMAND

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val sessionIdRaw = Args.requireString(args, "sessionId")
        val command = Args.requireString(args, "command").trim()
        val timeoutSeconds = Args.optionalInt(
            args, "timeoutSeconds",
            default = DEFAULT_TIMEOUT_SECONDS, min = 1, max = MAX_TIMEOUT_SECONDS,
        )

        val sessionId = SessionId(sessionIdRaw)
        val metadata = SessionRegistry.metadataOf(sessionId)
            ?: throw McpToolException(NOT_FOUND, "Sesión `$sessionIdRaw` no encontrada en el registro")
        SessionRegistry.sinkOf(sessionId)
            ?: throw McpToolException(UNAVAILABLE, "Sesión `$sessionIdRaw` sin sink: no es inyectable")

        val sample = SessionRegistry.lastLinesOf(sessionId, VENDOR_SAMPLE_LINES).joinToString("\n")
        val vendor = if (sample.isBlank()) Vendor.UNKNOWN else VendorDetector.detect(sample)
        val auditLogId = UUID.randomUUID().toString()

        // Error #8: vendor desconocido => no se adivina qué comando es seguro.
        if (vendor == Vendor.UNKNOWN) {
            auditRejected(auditLogId, sessionId, metadata.host, vendor, command,
                reason = "vendor no detectado")
            throw McpToolException(
                INVALID_ARGUMENT,
                "No pude detectar el vendor de la sesión `$sessionIdRaw` (buffer insuficiente o " +
                    "equipo desconocido): sin whitelist no ejecuto nada. Generá output en la " +
                    "sesión (p. ej. un Enter) o usá `propose_commands`.",
            )
        }

        // La whitelist corre SIEMPRE: es la invariante de seguridad de esta tool.
        val verdict = validatorProvider().validate(command, vendor)
        if (verdict is ReadOnlyValidation.Rejected) {
            auditRejected(auditLogId, sessionId, metadata.host, vendor, command, verdict.reason)
            throw McpToolException(
                INVALID_ARGUMENT,
                "Comando rechazado por la whitelist read-only: ${verdict.reason}",
            )
        }

        // Gate humano opcional (setting OFF => diálogo por cada comando).
        var commandToRun = command
        if (!allowWithoutApproval()) {
            val decision = approvalGate.reviewCommands(
                "(mcp run_readonly_command)", vendor,
                listOf(ClassifiedCommand(command, RiskLevel.SAFE)),
            )
            when (decision) {
                is ApprovalDecision.Reject -> {
                    auditRejected(auditLogId, sessionId, metadata.host, vendor, command,
                        reason = "rechazado por el operador")
                    return linkedMapOf(
                        "sessionId" to sessionIdRaw,
                        "command" to command,
                        "vendor" to vendor.displayName,
                        "approved" to false,
                        "output" to "",
                        "truncated" to false,
                        "timedOut" to false,
                        "durationMs" to 0,
                        "auditLogId" to auditLogId,
                    )
                }
                is ApprovalDecision.Approve -> {
                    val edited = decision.commands.filter { it.isNotBlank() }
                    if (edited.size != 1) {
                        throw McpToolException(
                            INVALID_ARGUMENT,
                            "Esta tool ejecuta UN solo comando; la edición del operador dejó ${edited.size}. " +
                                "Para bloques usá `propose_commands`.",
                        )
                    }
                    commandToRun = edited.single().trim()
                    val reVerdict = validatorProvider().validate(commandToRun, vendor)
                    if (reVerdict is ReadOnlyValidation.Rejected) {
                        auditRejected(auditLogId, sessionId, metadata.host, vendor, commandToRun, reVerdict.reason)
                        throw McpToolException(
                            INVALID_ARGUMENT,
                            "La edición del operador no es read-only (${reVerdict.reason}) — nada se ejecutó. " +
                                "Usá `propose_commands` para comandos mutativos.",
                        )
                    }
                }
            }
        }

        val result = try {
            runner.run(sessionId, vendor, commandToRun, timeoutSeconds * 1000L)
        } catch (e: SessionCommandRunner.SessionGoneException) {
            throw McpToolException(UNAVAILABLE, e.message ?: "Sesión no disponible")
        }

        val outputRedacted = redactor.redact(result.output, vendor)
        auditLog.append(
            AiAuditEntry(
                timestampMillis = System.currentTimeMillis(),
                sessionId = "${sessionId.value}#$auditLogId",
                host = metadata.host,
                vendor = vendor.displayName,
                prompt = "(mcp run_readonly_command)",
                commands = listOf(commandToRun),
                commandRisks = listOf(RiskLevel.SAFE),
                executedCount = 1,
                skippedCount = 0,
                failedCount = 0,
                rejected = false,
                outputTail = outputRedacted.take(AUDIT_OUTPUT_EXCERPT_CHARS),
            )
        )
        return linkedMapOf(
            "sessionId" to sessionIdRaw,
            "command" to commandToRun,
            "vendor" to vendor.displayName,
            "approved" to true,
            "output" to outputRedacted,
            "truncated" to result.truncated,
            "timedOut" to result.timedOut,
            "durationMs" to result.durationMs,
            "auditLogId" to auditLogId,
        )
    }

    private fun auditRejected(
        auditLogId: String,
        sessionId: SessionId,
        host: String?,
        vendor: Vendor,
        command: String,
        reason: String,
    ) {
        auditLog.append(
            AiAuditEntry(
                timestampMillis = System.currentTimeMillis(),
                sessionId = "${sessionId.value}#$auditLogId",
                host = host,
                vendor = vendor.takeIf { it != Vendor.UNKNOWN }?.displayName,
                prompt = "(mcp run_readonly_command)",
                commands = listOf(command),
                commandRisks = listOf(RiskLevel.SAFE),
                executedCount = 0,
                skippedCount = 1,
                failedCount = 0,
                rejected = true,
                outputTail = "rechazado: $reason",
            )
        )
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 15
        const val MAX_TIMEOUT_SECONDS = 120
        const val AUDIT_OUTPUT_EXCERPT_CHARS = 2048
        private const val VENDOR_SAMPLE_LINES = 64

        /**
         * El validador se relee del disco como mucho cada 30 s: el operador puede editar
         * el YAML sin reiniciar, sin pagar un parse por request.
         */
        fun cachedDefaultValidator(): () -> ReadOnlyCommandValidator {
            var cached: ReadOnlyCommandValidator? = null
            var loadedAt = 0L
            return {
                val now = System.currentTimeMillis()
                val current = cached
                if (current == null || now - loadedAt > 30_000) {
                    ReadOnlyCommandValidator.default().also {
                        cached = it
                        loadedAt = now
                    }
                } else current
            }
        }
    }
}
