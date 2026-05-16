package com.opentermx.mcp.handlers

import com.opentermx.ai.context.Vendor
import com.opentermx.ai.context.VendorDetector
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.NOT_FOUND
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions

/**
 * Handler de la tool `inspect_session`. Devuelve metadata + últimas N líneas del buffer.
 * El vendor se calcula con [VendorDetector] sobre el sample, igual que en el chat IA.
 */
class InspectSessionHandler : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.INSPECT_SESSION

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val sessionIdRaw = Args.requireString(args, "sessionId")
        val lastLines = Args.optionalInt(
            args, "lastLines",
            default = ToolDefinitions.DEFAULT_LAST_LINES,
            min = 1,
            max = ToolDefinitions.MAX_LAST_LINES,
        )
        val sessionId = SessionId(sessionIdRaw)
        val metadata = SessionRegistry.metadataOf(sessionId)
            ?: throw McpToolException(NOT_FOUND, "Sesión `$sessionIdRaw` no encontrada en el registro")

        val lines = SessionRegistry.lastLinesOf(sessionId, lastLines)
        val sample = lines.joinToString("\n")
        val vendor = if (sample.isBlank()) Vendor.UNKNOWN else VendorDetector.detect(sample)

        return linkedMapOf(
            "sessionId" to sessionId.value,
            "protocol" to metadata.protocol,
            "host" to metadata.host,
            "port" to metadata.port,
            "username" to metadata.username,
            "vendor" to vendor.displayName,
            "lines" to lines,
        )
    }
}