package com.opentermx.mcp.handlers

import com.opentermx.ai.context.Vendor
import com.opentermx.ai.context.VendorDetector
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions

/**
 * Handler de la tool `list_sessions`. Solo-lectura: agrega un snapshot del
 * [SessionRegistry] devolviendo el descriptor de cada sesión activa más una detección
 * de vendor barata sobre las últimas 64 líneas del buffer.
 */
class ListSessionsHandler : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.LIST_SESSIONS

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val sessions = SessionRegistry.activeSessions().map { descriptor ->
            val sample = SessionRegistry.lastLinesOf(descriptor.id, VENDOR_SAMPLE_LINES)
                .joinToString("\n")
            val vendor = if (sample.isBlank()) Vendor.UNKNOWN else VendorDetector.detect(sample)
            linkedMapOf<String, Any?>(
                "sessionId" to descriptor.id.value,
                "protocol" to descriptor.metadata.protocol,
                "host" to descriptor.metadata.host,
                "port" to descriptor.metadata.port,
                "username" to descriptor.metadata.username,
                "vendor" to vendor.displayName,
            )
        }
        return linkedMapOf("sessions" to sessions)
    }

    private companion object {
        const val VENDOR_SAMPLE_LINES = 64
    }
}