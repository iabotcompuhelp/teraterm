package com.opentermx.app.inventory

import com.opentermx.app.settings.AppSettings
import com.opentermx.app.settings.SavedAuthKind
import com.opentermx.app.settings.SavedConnection
import com.opentermx.app.settings.SavedConnections
import com.opentermx.common.crypto.SecretCipher
import com.opentermx.mcp.telemetry.TelemetryStore
import com.opentermx.netparsers.Vendor
import java.util.UUID

data class InventoryCommitResult(
    val settings: AppSettings,
    val imported: Int,
    val updated: Int,
    val databaseRecorded: Int,
)

class InventoryImportCommitService(private val telemetry: TelemetryStore) {
    fun commit(preview: InventoryImportPreview, current: AppSettings, actor: String): InventoryCommitResult {
        require(preview.valid) { "la vista previa contiene errores" }
        require(actor.isNotBlank()) { "actor no puede estar vacío" }
        var connections = current.savedConnections
        var imported = 0
        var updated = 0
        var databaseRecorded = 0
        preview.rows.forEach { row ->
            val previous = SavedConnections.findMostRecent(connections, row.protocol, row.ip, row.port)
            val id = previous?.id ?: UUID.randomUUID().toString()
            val credentialRef = "saved:$id"
            val entry = SavedConnection(
                id = id,
                protocol = row.protocol,
                host = row.ip,
                port = row.port,
                username = row.username,
                authKind = if (row.protocol == "SSH") SavedAuthKind.PASSWORD else SavedAuthKind.NONE,
                secret = if (row.protocol == "SSH") SecretCipher.encrypt(row.password) else null,
                label = row.name,
                alias = aliasFor(row.name, row.ip),
                deviceType = row.deviceType,
                tags = listOf("excel-import"),
                vendor = row.vendor,
                model = row.model,
                baseMac = row.mac,
                serialNumber = row.serialNumber,
                credentialRef = credentialRef,
            )
            connections = SavedConnections.upsert(connections, entry, bumpLastUsed = false)
            if (previous == null) imported++ else updated++

            val db = telemetry.db()
            if (db != null && telemetry.isAvailable()) {
                val deviceId = db.devices.upsertInventory(
                    hostname = row.name,
                    mgmtAddress = row.ip,
                    port = row.port,
                    protocol = row.protocol,
                    vendor = vendorOf(row.vendor),
                    model = row.model,
                    role = row.deviceType,
                    credentialRef = credentialRef,
                    baseMac = row.mac,
                    serialNumber = row.serialNumber,
                    source = "excel_import",
                )
                if (deviceId != null) {
                    val activityId = db.activities.append(
                        deviceId = deviceId,
                        deviceName = row.name,
                        mgmtAddress = row.ip,
                        actor = actor,
                        activityType = if (previous == null) "INVENTORY_IMPORTED" else "INVENTORY_UPDATED",
                        summary = "Equipo importado desde Excel",
                        outcome = "SUCCESS",
                        source = "ui:inventory_excel_import",
                        correlationId = "inventory-import-$id",
                        detailsJson = "{\"rowNumber\":${row.rowNumber},\"credentialRef\":\"$credentialRef\"}",
                    )
                    if (activityId != null) databaseRecorded++
                }
            }
        }
        return InventoryCommitResult(current.copy(savedConnections = connections), imported, updated, databaseRecorded)
    }

    private fun aliasFor(name: String, ip: String): String {
        val normalized = name.lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-')
        return normalized.ifBlank { "device-${ip.replace(':', '-').replace('.', '-')}" }
    }

    private fun vendorOf(value: String): Vendor {
        val normalized = value.uppercase().replace(Regex("[^A-Z0-9]"), "_")
        return when {
            normalized.contains("ARUBA") || normalized.contains("AOS_CX") -> Vendor.ARUBA_AOSCX
            normalized.contains("CISCO") && normalized.contains("NX") -> Vendor.CISCO_NXOS
            normalized.contains("CISCO") -> Vendor.CISCO_IOS
            normalized.contains("FORTI") -> Vendor.FORTINET
            normalized.contains("HUAWEI") -> Vendor.HUAWEI_VRP
            normalized.contains("MIKROTIK") -> Vendor.MIKROTIK
            normalized.contains("JUNIPER") -> Vendor.JUNIPER_JUNOS
            else -> Vendor.UNKNOWN
        }
    }
}
