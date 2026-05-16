package com.opentermx.mcp.handlers

import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.NOT_FOUND
import com.opentermx.mcp.security.TailManager
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions

/**
 * Handler de `tail_session`. Marca la sesión como "tailed" en el [TailManager]; el
 * [com.opentermx.mcp.McpServer] está suscripto a `EventBus.DataReceived` y va a emitir
 * `notifications/sessions/output` para cada chunk que llegue a esa sesión, hasta el
 * auto-stop (30 min).
 */
class TailSessionHandler(
    private val tailManager: TailManager,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.TAIL_SESSION

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val sessionIdRaw = Args.requireString(args, "sessionId")
        val sessionId = SessionId(sessionIdRaw)
        SessionRegistry.metadataOf(sessionId)
            ?: throw McpToolException(NOT_FOUND, "Sesión `$sessionIdRaw` no encontrada")
        tailManager.start(sessionIdRaw)
        return linkedMapOf(
            "started" to true,
            "expiresAtMillis" to (System.currentTimeMillis() + TailManager.DEFAULT_TTL_MILLIS),
            "error" to null,
        )
    }
}