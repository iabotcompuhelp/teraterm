package com.opentermx.server.agent

import com.opentermx.ai.context.Vendor
import com.opentermx.ai.context.VendorDetector
import com.opentermx.mcp.handlers.InspectSessionHandler
import com.opentermx.mcp.handlers.ListSessionsHandler
import com.opentermx.mcp.handlers.ToolHandler
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions

class FederatedListSessionsHandler(
    private val registry: AgentRegistry,
    private val contexts: FederatedDeviceContextStore? = null,
) : ToolHandler {
    override val definition: ToolDef = ToolDefinitions.LIST_SESSIONS
    private val local = ListSessionsHandler()

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val sessions = (local.invoke(args)["sessions"] as List<Map<String, Any?>>).toMutableList()
        registry.sessions().forEach { remote ->
            val s = remote.snapshot
            val vendor = detect(s.lines)
            val row = linkedMapOf<String, Any?>(
                "sessionId" to remote.id,
                "protocol" to s.protocol,
                "host" to s.host,
                "port" to s.port,
                "username" to s.username,
                "vendor" to vendor.displayName,
            )
            contexts?.find(remote.agentId, s.sessionId)?.let { row.putAll(it.asToolFields()) }
            sessions += row
        }
        return mapOf("sessions" to sessions)
    }
}

class FederatedInspectSessionHandler(
    private val registry: AgentRegistry,
    private val contexts: FederatedDeviceContextStore? = null,
) : ToolHandler {
    override val definition: ToolDef = ToolDefinitions.INSPECT_SESSION
    private val local = InspectSessionHandler()

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val id = (args["sessionId"] as? String).orEmpty()
        val remote = registry.find(id) ?: return local.invoke(args)
        val count = (args["lastLines"] as? Number)?.toInt() ?: ToolDefinitions.DEFAULT_LAST_LINES
        val s = remote.snapshot
        val row = linkedMapOf<String, Any?>(
            "sessionId" to remote.id,
            "protocol" to s.protocol,
            "host" to s.host,
            "port" to s.port,
            "username" to s.username,
            "vendor" to detect(s.lines).displayName,
            "lines" to s.lines.takeLast(count),
        )
        contexts?.find(remote.agentId, s.sessionId)?.let { row.putAll(it.asToolFields()) }
        return row
    }
}

private fun detect(lines: List<String>): Vendor =
    if (lines.isEmpty()) Vendor.UNKNOWN else VendorDetector.detect(lines.joinToString("\n"))

private fun FederatedDeviceContext.asToolFields(): Map<String, Any?> = linkedMapOf(
    "deviceContext" to linkedMapOf(
        "managementAddress" to managementAddress,
        "vendor" to vendor,
        "model" to model,
        "osVersion" to osVersion,
        "serialNumbers" to serialNumbers,
        "hostname" to hostname,
        "confidence" to confidence,
        "sourceCommand" to sourceCommand,
        "sourceTaskId" to sourceTaskId,
        "updatedAtMillis" to updatedAtMillis,
        "allowedDiscoveryCommands" to allowedDiscoveryCommands,
    ),
)
