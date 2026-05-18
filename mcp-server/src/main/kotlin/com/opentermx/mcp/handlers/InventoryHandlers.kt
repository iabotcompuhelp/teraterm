package com.opentermx.mcp.handlers

import com.opentermx.common.ai.SessionRegistry
import com.opentermx.mcp.inventory.InventoryDevice
import com.opentermx.mcp.inventory.InventoryProvider
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions

/**
 * Phase 3 Fase 2 — handlers de Device Registry. Ambos son read-only y nunca devuelven
 * credenciales: el [InventoryDevice] que producen ya garantiza eso a nivel tipo.
 */
class InventoryListHandler(
    private val provider: InventoryProvider,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.INVENTORY_LIST

    @Suppress("UNCHECKED_CAST")
    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val tags = (args["tags"] as? List<*>)?.mapNotNull { it as? String }
        val groups = (args["groups"] as? List<*>)?.mapNotNull { it as? String }
        val deviceType = args["deviceType"] as? String
        val devices = provider.list(tags, groups, deviceType)
        return linkedMapOf(
            "devices" to devices.map { it.toMap() },
        )
    }
}

class InventoryDescribeHandler(
    private val provider: InventoryProvider,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.INVENTORY_DESCRIBE

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val alias = Args.requireString(args, "alias")
        val device = provider.byAlias(alias)
        return linkedMapOf(
            "device" to device?.toMap(),
            "activeSessionId" to device?.let { findActiveSessionId(it) },
        )
    }

    private fun findActiveSessionId(device: InventoryDevice): String? =
        SessionRegistry.activeSessions()
            .firstOrNull { desc ->
                desc.metadata.host.equals(device.host, ignoreCase = true) && desc.metadata.port == device.port
            }
            ?.id?.value
}

/**
 * Mapea el DTO a un Map JSON serializable. Mantengo el mapeo manual (en vez de
 * `ObjectMapper.convertValue`) para garantizar que NO se filtra ningún campo sensible
 * por accidente — si en el futuro `InventoryDevice` gana un field nuevo, el compilador
 * obliga a decidir si entra acá.
 */
private fun InventoryDevice.toMap(): Map<String, Any?> = linkedMapOf(
    "alias" to alias,
    "protocol" to protocol,
    "host" to host,
    "port" to port,
    "username" to username,
    "deviceType" to deviceType,
    "tags" to tags,
    "groups" to groups,
    "displayLabel" to displayLabel,
    "hasActiveSession" to (com.opentermx.common.ai.SessionRegistry.activeSessions()
        .any { it.metadata.host.equals(host, ignoreCase = true) && it.metadata.port == port }),
)
