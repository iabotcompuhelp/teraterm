package com.opentermx.app.ui.mcp

import com.opentermx.app.settings.AppSettings
import com.opentermx.app.settings.SavedConnection
import com.opentermx.app.settings.SavedConnections
import com.opentermx.mcp.inventory.InventoryDevice
import com.opentermx.mcp.inventory.InventoryProvider

/**
 * Phase 3 Fase 2 — Device Registry.
 *
 * Adapter que expone `AppSettings.savedConnections` como inventario MCP. Solo aparecen
 * las entradas que tienen `alias` definido — sin alias, no son addresables por nombre
 * lógico y por lo tanto no son inventory desde el punto de vista de las tools.
 *
 * Las credenciales del `SavedConnection` quedan en el módulo `app`; lo que viaja al
 * `mcp-server` es exclusivamente lo declarado en [InventoryDevice] (sin secret ni keyPath).
 */
class SettingsInventoryProvider(
    private val settingsProvider: () -> AppSettings,
) : InventoryProvider {

    override fun list(
        tagsAny: List<String>?,
        groupsAny: List<String>?,
        deviceType: String?,
    ): List<InventoryDevice> {
        val saved = settingsProvider().savedConnections
        return SavedConnections.filterForInventory(saved, tagsAny, groupsAny, deviceType)
            .map { it.toInventoryDevice() }
    }

    override fun byAlias(alias: String): InventoryDevice? {
        val saved = settingsProvider().savedConnections
        return SavedConnections.findByAlias(saved, alias)?.toInventoryDevice()
    }

    private fun SavedConnection.toInventoryDevice(): InventoryDevice = InventoryDevice(
        alias = alias!!,                // pre-filter garantiza no-null
        protocol = protocol,
        host = host,
        port = port,
        username = username,
        deviceType = deviceType,
        tags = tags,
        groups = groups,
        savedConnectionId = id,
        displayLabel = displayLabel(),
    )
}
