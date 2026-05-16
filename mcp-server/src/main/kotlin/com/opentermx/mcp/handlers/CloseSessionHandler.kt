package com.opentermx.mcp.handlers

import com.opentermx.ai.context.Vendor
import com.opentermx.ai.safety.ClassifiedCommand
import com.opentermx.ai.safety.RiskLevel
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.NOT_FOUND
import com.opentermx.mcp.security.ApprovalDecision
import com.opentermx.mcp.security.ApprovalGate
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions

/**
 * Handler de `close_session`. Pasa por el [ApprovalGate] porque cerrar puede interrumpir
 * trabajo del operador. Si aprueba, llama a `Connection.disconnect()` — el unregister
 * del [SessionRegistry] lo dispara el handler de estado cuando la conexión se cierra.
 */
class CloseSessionHandler(
    private val approvalGate: ApprovalGate,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.CLOSE_SESSION

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val sessionIdRaw = Args.requireString(args, "sessionId")
        val reason = Args.optionalString(args, "reason")
        val sessionId = SessionId(sessionIdRaw)
        val metadata = SessionRegistry.metadataOf(sessionId)
            ?: throw McpToolException(NOT_FOUND, "Sesión `$sessionIdRaw` no encontrada")

        val summary = listOfNotNull(
            "close_session: ${sessionId.value}",
            "host: ${metadata.host ?: "(local)"}",
            reason?.let { "reason: $it" },
        )
        val decision = approvalGate.reviewCommands(
            prompt = "Solicitud MCP de cerrar sesión `${sessionId.value}`",
            vendor = Vendor.UNKNOWN,
            classifications = summary.map { ClassifiedCommand(it, RiskLevel.CONFIG) },
        )
        if (decision is ApprovalDecision.Reject) {
            return linkedMapOf("approved" to false, "closed" to false, "error" to "operador rechazó el cierre")
        }
        val connection = SessionRegistry.connectionOf(sessionId)
        if (connection == null) {
            return linkedMapOf(
                "approved" to true,
                "closed" to false,
                "error" to "sesión sin Connection registrada — el unregister ya fue hecho por la UI",
            )
        }
        runCatching { connection.disconnect() }
            .onFailure {
                return linkedMapOf(
                    "approved" to true,
                    "closed" to false,
                    "error" to "disconnect lanzó: ${it.message ?: it.javaClass.simpleName}",
                )
            }
        return linkedMapOf("approved" to true, "closed" to true, "error" to null)
    }
}