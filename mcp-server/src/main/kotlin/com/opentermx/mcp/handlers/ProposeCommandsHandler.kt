package com.opentermx.mcp.handlers

import com.opentermx.ai.audit.AiAuditEntry
import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.ai.context.Vendor
import com.opentermx.ai.context.VendorDetector
import com.opentermx.ai.safety.CredentialRedactor
import com.opentermx.ai.safety.RiskClassifier
import com.opentermx.ai.safety.RiskLevel
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.NOT_FOUND
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.UNAVAILABLE
import com.opentermx.mcp.security.ApprovalDecision
import com.opentermx.mcp.security.ApprovalGate
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions
import java.util.UUID

/**
 * Handler de la tool MUTATIVA `propose_commands`.
 *
 * Flujo:
 *  1. Validar que `sessionId` exista en el [SessionRegistry] y tenga un `CommandSink`
 *     (sin sink, no hay forma de enviar comandos: error).
 *  2. Detectar el vendor a partir del buffer reciente (`VendorDetector`).
 *  3. Clasificar los comandos con [RiskClassifier].
 *  4. Pedir aprobación al operador vía [ApprovalGate]. Si rechaza, no se ejecuta nada;
 *     se devuelve `approved=false` y se loguea en [AiAuditLog].
 *  5. Si aprueba, inyectar los comandos línea por línea por el sink, contando
 *     ejecutados y fallidos.
 *  6. Loguear todo (`AiAuditLog`) y devolver el resumen estructurado.
 *
 * La invariante de seguridad NO se negocia: aunque la lista llegue vacía o el operador
 * ya esté frente al panel, el approval gate se invoca siempre.
 */
class ProposeCommandsHandler(
    private val approvalGate: ApprovalGate,
    private val auditLog: AiAuditLog = AiAuditLog(),
    private val injectDelayMillis: Long = DEFAULT_INJECT_DELAY_MILLIS,
    private val redactor: CredentialRedactor = CredentialRedactor(),
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.PROPOSE_COMMANDS

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val sessionIdRaw = Args.requireString(args, "sessionId")
        val commands = Args.requireStringList(args, "commands")
        val rationale = Args.optionalString(args, "rationale").orEmpty()

        val sessionId = SessionId(sessionIdRaw)
        val metadata = SessionRegistry.metadataOf(sessionId)
            ?: throw McpToolException(NOT_FOUND, "Sesión `$sessionIdRaw` no encontrada en el registro")
        val sink = SessionRegistry.sinkOf(sessionId)
            ?: throw McpToolException(UNAVAILABLE, "Sesión `$sessionIdRaw` sin sink: no es inyectable")

        val sample = SessionRegistry.lastLinesOf(sessionId, VENDOR_SAMPLE_LINES).joinToString("\n")
        val vendor = if (sample.isBlank()) Vendor.UNKNOWN else VendorDetector.detect(sample)
        val classifications = RiskClassifier.classify(commands, vendor)
        val riskSummary = riskSummaryOf(classifications.map { it.risk })
        val auditLogId = UUID.randomUUID().toString()

        val decision = approvalGate.reviewCommands(rationale, vendor, classifications)

        return when (decision) {
            is ApprovalDecision.Reject -> {
                logAudit(
                    auditLogId, sessionId, metadata.host, vendor,
                    rationale, commands, classifications.map { it.risk },
                    executed = 0, failed = 0, rejected = true, outputTail = "",
                )
                linkedMapOf(
                    "approved" to false,
                    "executed" to 0,
                    "rejected" to commands.size,
                    "auditLogId" to auditLogId,
                    "output" to null,
                    "riskSummary" to riskSummary,
                )
            }
            is ApprovalDecision.Approve -> {
                val approved = decision.commands
                var executed = 0
                var failed = 0
                for (cmd in approved) {
                    val ok = runCatching { sink.sendLine(cmd) }.getOrDefault(false)
                    if (ok) executed++ else failed++
                    if (injectDelayMillis > 0) {
                        kotlinx.coroutines.delay(injectDelayMillis)
                    }
                }
                val tailRaw = SessionRegistry.lastLinesOf(sessionId, 20).joinToString("\n")
                val tailRedacted = redactor.redact(tailRaw, vendor)
                logAudit(
                    auditLogId, sessionId, metadata.host, vendor,
                    rationale, approved, decision.risks,
                    executed = executed, failed = failed, rejected = false, outputTail = tailRedacted,
                )
                linkedMapOf(
                    "approved" to true,
                    "executed" to executed,
                    "rejected" to (commands.size - approved.size),
                    "auditLogId" to auditLogId,
                    "output" to tailRedacted,
                    "riskSummary" to riskSummaryOf(decision.risks),
                )
            }
        }
    }

    private fun logAudit(
        auditLogId: String,
        sessionId: SessionId,
        host: String?,
        vendor: Vendor,
        prompt: String,
        commands: List<String>,
        risks: List<RiskLevel>,
        executed: Int,
        failed: Int,
        rejected: Boolean,
        outputTail: String,
    ) {
        auditLog.append(
            AiAuditEntry(
                timestampMillis = System.currentTimeMillis(),
                sessionId = "${sessionId.value}#$auditLogId",
                host = host,
                vendor = vendor.takeIf { it != Vendor.UNKNOWN }?.displayName,
                prompt = prompt.ifBlank { "(mcp propose_commands)" },
                commands = commands,
                commandRisks = risks,
                executedCount = executed,
                skippedCount = (commands.size - executed - failed).coerceAtLeast(0),
                failedCount = failed,
                rejected = rejected,
                outputTail = outputTail,
            )
        )
    }

    private fun riskSummaryOf(risks: List<RiskLevel>): Map<String, Int> = linkedMapOf(
        "safe" to risks.count { it == RiskLevel.SAFE },
        "config" to risks.count { it == RiskLevel.CONFIG },
        "dangerous" to risks.count { it == RiskLevel.DANGEROUS },
    )

    companion object {
        const val DEFAULT_INJECT_DELAY_MILLIS = 120L
        private const val VENDOR_SAMPLE_LINES = 64
    }
}