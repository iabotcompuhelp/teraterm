package com.opentermx.mcp.handlers

import com.opentermx.ai.audit.AiAuditEntry
import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.ai.safety.RiskLevel
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.INVALID_ARGUMENT
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.NOT_FOUND
import com.opentermx.mcp.operation.OperationRegistry
import com.opentermx.mcp.security.ApprovalTokens
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions
import java.util.UUID

/**
 * Phase 3 Fase 3 — handler de `compliance_evaluate`.
 *
 * Rol esperado: COMPLIANCE (enforce server-side via [com.opentermx.mcp.security.RoleAccessControl]).
 * Si `approved=true`, firma un [com.opentermx.mcp.security.ApprovalToken] que el OPERATOR
 * incluye en `propose_commands`. La auditoría se persiste en el mismo CSV que usa el
 * `propose_commands` para que la trazabilidad cruzada sea uniforme.
 */
class ComplianceEvaluateHandler(
    private val registry: OperationRegistry,
    private val secretProvider: () -> ByteArray,
    private val auditLog: AiAuditLog = AiAuditLog(),
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.COMPLIANCE_EVALUATE

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val operationId = Args.requireString(args, "operationId")
        val proposedCommands = Args.requireStringList(args, "proposedCommands")
        val deviceAlias = Args.optionalString(args, "deviceAlias")
        val approved = (args["approved"] as? Boolean) ?: true
        val reasons = (args["reasons"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        val ttlMillis = (args["ttlMillis"] as? Number)?.toLong() ?: ApprovalTokens.DEFAULT_TTL_MILLIS

        val record = registry.forOperationId(operationId)
            ?: throw McpToolException(NOT_FOUND, "Operation `$operationId` no encontrada o ya cerrada")
        if (proposedCommands.isEmpty()) {
            throw McpToolException(INVALID_ARGUMENT, "proposedCommands no puede estar vacía")
        }

        val auditLogId = UUID.randomUUID().toString()
        val token = if (approved) {
            ApprovalTokens.issue(
                operationId = operationId,
                deviceAlias = deviceAlias,
                commands = proposedCommands,
                secret = secretProvider(),
                ttlMillis = ttlMillis,
            )
        } else null

        // Auditamos cada evaluación con un riskSummary de "COMPLIANCE_REVIEW" (no son
        // riesgos clasificados — son la decisión del compliance LLM).
        runCatching {
            auditLog.append(
                AiAuditEntry(
                    timestampMillis = System.currentTimeMillis(),
                    sessionId = "compliance:${operationId}",
                    host = deviceAlias,
                    vendor = "compliance_evaluate",
                    prompt = reasons.joinToString(" | "),
                    commands = proposedCommands,
                    commandRisks = proposedCommands.map { RiskLevel.CONFIG },
                    executedCount = 0,
                    skippedCount = 0,
                    failedCount = 0,
                    rejected = !approved,
                    outputTail = if (approved) "approved" else "rejected",
                )
            )
        }

        return linkedMapOf(
            "approved" to approved,
            "approvalToken" to token,
            "reasons" to reasons,
            "auditLogId" to auditLogId,
            "operationId" to operationId,
        )
    }
}
