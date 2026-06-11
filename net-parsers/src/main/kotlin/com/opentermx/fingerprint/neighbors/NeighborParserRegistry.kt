package com.opentermx.fingerprint.neighbors

import com.opentermx.netparsers.OutputParser
import com.opentermx.netparsers.Vendor

/**
 * Catálogo de parsers de vecinos y resolución comando⇄parser por vendor (subfase 5A),
 * espejo del `ParserRegistry` de la Fase 2.
 */
object NeighborParserRegistry {

    private val ciscoCdp = CiscoCdpNeighborsDetailParser()
    private val ciscoIosXeCdp = CiscoCdpNeighborsDetailParser(vendor = Vendor.CISCO_IOSXE)
    private val ciscoNxosCdp = CiscoCdpNeighborsDetailParser(vendor = Vendor.CISCO_NXOS)
    private val ciscoLldp = CiscoLldpNeighborsDetailParser()
    private val ciscoIosXeLldp = CiscoLldpNeighborsDetailParser(vendor = Vendor.CISCO_IOSXE)
    private val huaweiLldp = HuaweiLldpNeighborParser()
    private val mikrotikMndp = MikrotikIpNeighborParser()

    fun all(): List<OutputParser<List<NeighborEntry>>> = listOf(
        ciscoCdp, ciscoIosXeCdp, ciscoNxosCdp,
        ciscoLldp, ciscoIosXeLldp,
        huaweiLldp, mikrotikMndp,
    )

    /**
     * Comando de descubrimiento de vecinos para [vendor]. `null` = vendor sin soporte
     * en 5A (Fortinet, Aruba, Juniper) — el fingerprint sigue sin topología.
     */
    fun neighborCommand(vendor: Vendor): String? = when (vendor) {
        Vendor.CISCO_IOS, Vendor.CISCO_IOSXE, Vendor.CISCO_NXOS -> "show cdp neighbors detail"
        Vendor.HUAWEI_VRP -> "display lldp neighbor"
        Vendor.MIKROTIK -> "/ip neighbor print detail"
        else -> null
    }

    /** Parser que entiende el output de [command] para [vendor], o null. */
    fun forCommand(vendor: Vendor, command: String): OutputParser<List<NeighborEntry>>? =
        all().firstOrNull { it.vendor == vendor && it.commandPattern.containsMatchIn(command.trim()) }
}
