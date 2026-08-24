package com.opentermx.mcp.backups

import com.opentermx.ai.context.Vendor
import com.opentermx.ai.context.VendorDetector
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.exec.SessionCommandRunner
import com.opentermx.mcp.inventory.InventoryProvider

/** Captura configuración directamente desde una sesión activa resuelta por inventario. */
class CliBackupCaptureSource(
    private val inventory: InventoryProvider,
    private val runner: SessionCommandRunner,
) : BackupCaptureSource {
    override suspend fun capture(deviceAlias: String?, sessionId: String?): CapturedConfiguration {
        val device = deviceAlias?.let(inventory::byAlias)
        if (deviceAlias != null && device == null) throw IllegalArgumentException("device no inventariado")
        val session = when {
            sessionId != null -> SessionRegistry.activeSessions().firstOrNull { it.id == SessionId(sessionId) }
            device != null -> SessionRegistry.activeSessions().firstOrNull {
                it.metadata.host.equals(device!!.host, ignoreCase = true) && it.metadata.port == device!!.port
            }
            else -> null
        } ?: throw IllegalStateException("el dispositivo no tiene una sesión activa")
        val detected = VendorDetector.detect(SessionRegistry.lastLinesOf(session.id, 160).joinToString("\n"))
        val vendor = if (detected != Vendor.UNKNOWN) detected else vendorOf(device?.deviceType)
        val command = captureCommand(vendor)
            ?: throw IllegalStateException("vendor `${device?.deviceType ?: vendor.name}` sin comando de backup soportado")
        val result = runner.run(session.id, vendor, command, CAPTURE_TIMEOUT_MILLIS)
        if (result.timedOut) throw IllegalStateException("la captura expiró antes de recibir el prompt final")
        if (result.truncated) throw IllegalStateException("la configuración excede el límite de captura; backup rechazado")
        if (result.output.isBlank()) throw IllegalStateException("el equipo devolvió una configuración vacía")
        return CapturedConfiguration(vendor, result.output, "adapter:CLI_SSH")
    }

    companion object {
        private const val CAPTURE_TIMEOUT_MILLIS = 120_000L

        internal fun captureCommand(vendor: Vendor): String? = when (vendor) {
            Vendor.CISCO_IOS, Vendor.CISCO_IOS_XE, Vendor.CISCO_NX_OS, Vendor.ARUBA_OS -> "show running-config"
            Vendor.HUAWEI_VRP, Vendor.HPE_COMWARE -> "display current-configuration"
            Vendor.JUNIPER_JUNOS -> "show configuration"
            Vendor.FORTINET_FORTIOS -> "show full-configuration"
            Vendor.MIKROTIK_ROUTEROS -> "export hide-sensitive"
            Vendor.UNKNOWN -> null
        }

        internal fun vendorOf(deviceType: String?): Vendor = when (deviceType?.lowercase()) {
            "cisco_ios" -> Vendor.CISCO_IOS
            "cisco_iosxe", "cisco_ios_xe" -> Vendor.CISCO_IOS_XE
            "cisco_nxos", "cisco_nx_os" -> Vendor.CISCO_NX_OS
            "aruba_os", "aruba_aoss" -> Vendor.ARUBA_OS
            "huawei_vrp" -> Vendor.HUAWEI_VRP
            "hpe_comware" -> Vendor.HPE_COMWARE
            "juniper_junos" -> Vendor.JUNIPER_JUNOS
            "fortinet", "fortinet_fortios" -> Vendor.FORTINET_FORTIOS
            "mikrotik_routeros" -> Vendor.MIKROTIK_ROUTEROS
            else -> Vendor.UNKNOWN
        }
    }
}
