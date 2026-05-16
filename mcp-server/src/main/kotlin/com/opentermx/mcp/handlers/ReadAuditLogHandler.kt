package com.opentermx.mcp.handlers

import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.ai.safety.CredentialRedactor
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions

/**
 * Handler de la tool `read_audit_log`. Filtra `AiAuditLog` por session/rango temporal y
 * devuelve hasta [ToolDefinitions.MAX_AUDIT_LIMIT] entradas, con [CredentialRedactor]
 * aplicado a `commands` y `outputTail` antes de devolver.
 *
 * El audit log puede contener `outputTail` con configuración cruda del dispositivo —
 * incluyendo `enable secret` y community strings — y commands generados por el LLM que
 * mencionan credenciales. La redacción es no-negociable acá.
 */
class ReadAuditLogHandler(
    private val auditLog: AiAuditLog = AiAuditLog(),
    private val redactor: CredentialRedactor = CredentialRedactor(),
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.READ_AUDIT_LOG

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val sessionId = Args.optionalString(args, "sessionId")
        val since = (args["sinceMillis"] as? Number)?.toLong()
        val until = (args["untilMillis"] as? Number)?.toLong()
        val limit = Args.optionalInt(
            args, "limit",
            default = ToolDefinitions.DEFAULT_AUDIT_LIMIT,
            min = 1,
            max = ToolDefinitions.MAX_AUDIT_LIMIT,
        )

        val entries = auditLog.read(sessionId, since, until, limit).map { entry ->
            linkedMapOf<String, Any?>(
                "timestampMillis" to entry.timestampMillis,
                "sessionId" to entry.sessionId,
                "host" to entry.host,
                "vendor" to entry.vendor,
                "prompt" to redactor.redact(entry.prompt),
                "commands" to redactor.redactLines(entry.commands),
                "executedCount" to entry.executedCount,
                "failedCount" to entry.failedCount,
                "rejected" to entry.rejected,
                "outputTail" to redactor.redact(entry.outputTail),
            )
        }
        return linkedMapOf("entries" to entries)
    }
}