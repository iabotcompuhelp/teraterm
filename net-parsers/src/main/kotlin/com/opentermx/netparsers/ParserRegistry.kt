package com.opentermx.netparsers

import com.opentermx.netparsers.parsers.ArubaCxShowInterfaceParser
import com.opentermx.netparsers.parsers.CiscoIosShowInterfacesParser
import com.opentermx.netparsers.parsers.FortinetDiagNicParser
import com.opentermx.netparsers.parsers.FortinetGetSystemInterfaceParser
import com.opentermx.netparsers.parsers.HuaweiDisplayInterfaceParser
import com.opentermx.netparsers.parsers.MikrotikEthernetMonitorParser
import com.opentermx.netparsers.parsers.MikrotikPrintStatsParser
import com.opentermx.netparsers.parsers.NxosShowInterfaceParser

/**
 * Catálogo de parsers de interfaces y resolución comando⇄parser por vendor. Es la cara
 * pública del módulo para `mcp-server`: las tools de alto nivel piden acá QUÉ comando
 * ejecutar para un vendor y CON QUÉ parser interpretar el output.
 */
object ParserRegistry {

    private val ciscoIos = CiscoIosShowInterfacesParser()
    private val ciscoIosXe = CiscoIosShowInterfacesParser(vendor = Vendor.CISCO_IOSXE)
    private val nxos = NxosShowInterfaceParser()
    private val huawei = HuaweiDisplayInterfaceParser()
    private val arubaCx = ArubaCxShowInterfaceParser()
    private val fortinetGetSystem = FortinetGetSystemInterfaceParser()
    private val fortinetDiagNic = FortinetDiagNicParser()
    private val mikrotikPrintStats = MikrotikPrintStatsParser()
    private val mikrotikEthernetMonitor = MikrotikEthernetMonitorParser()

    /** Todos los parsers registrados (para tests de basura y discovery). */
    fun all(): List<OutputParser<List<InterfaceStats>>> = listOf(
        ciscoIos, ciscoIosXe, nxos, huawei, arubaCx,
        fortinetGetSystem, fortinetDiagNic,
        mikrotikPrintStats, mikrotikEthernetMonitor,
    )

    /**
     * Comando de interfaces para [vendor]. Con [interfaceName], los vendors que tienen
     * forma puntual la usan (FortiOS cambia de comando: el puntual SÍ trae contadores).
     * `null` = vendor sin soporte de telemetría (el handler devuelve error claro).
     */
    fun interfaceStatsCommand(vendor: Vendor, interfaceName: String? = null): String? = when (vendor) {
        Vendor.CISCO_IOS, Vendor.CISCO_IOSXE ->
            "show interfaces" + (interfaceName?.let { " $it" } ?: "")
        Vendor.CISCO_NXOS ->
            "show interface" + (interfaceName?.let { " $it" } ?: "")
        Vendor.HUAWEI_VRP ->
            "display interface" + (interfaceName?.let { " $it" } ?: "")
        Vendor.ARUBA_AOSCX ->
            "show interface" + (interfaceName?.let { " $it" } ?: "")
        Vendor.FORTINET ->
            if (interfaceName == null) "get system interface"
            else "diagnose hardware deviceinfo nic $interfaceName"
        Vendor.MIKROTIK -> "/interface print stats"
        else -> null
    }

    /** Parser que entiende el output de [command] para [vendor], o null. */
    fun forCommand(vendor: Vendor, command: String): OutputParser<List<InterfaceStats>>? =
        all().firstOrNull { it.vendor == vendor && it.commandPattern.containsMatchIn(command.trim()) }
}
