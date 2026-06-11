package com.opentermx.fingerprint.neighbors

import com.opentermx.netparsers.OutputParser
import com.opentermx.netparsers.ParseResult
import com.opentermx.netparsers.Vendor

/**
 * `show lldp neighbors detail` (IOS/IOS-XE): bloques que arrancan en `Local Intf:`.
 * Si el vecino no anuncia System Name, se usa el Chassis id como identificador (es lo
 * único que hay); sin ninguno de los dos el bloque se descarta con warning.
 */
class CiscoLldpNeighborsDetailParser(
    override val vendor: Vendor = Vendor.CISCO_IOS,
) : OutputParser<List<NeighborEntry>> {

    override val commandPattern = Regex("""^show\s+lldp\s+neighbors\s+detail$""", RegexOption.IGNORE_CASE)

    override fun parse(raw: String): ParseResult<List<NeighborEntry>> {
        if (!raw.contains("Local Intf:")) {
            if (TOTAL_ZERO.containsMatchIn(raw)) return ParseResult.Success(emptyList())
            return ParseResult.Failure("sin bloques `Local Intf:` de LLDP", raw.take(300))
        }
        val warnings = mutableListOf<String>()
        val entries = raw.split(Regex("""(?=Local Intf:)""")).mapNotNull { block ->
            val local = field(block, "Local Intf") ?: return@mapNotNull null
            val name = field(block, "System Name")?.takeIf { it != "- not advertised" }
                ?: field(block, "Chassis id")
            if (name == null) {
                warnings += "bloque LLDP en `$local` sin System Name ni Chassis id — descartado"
                return@mapNotNull null
            }
            NeighborEntry(
                localInterface = local,
                remoteHostname = name,
                remotePort = field(block, "Port id"),
                capabilities = field(block, "System Capabilities")
                    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: emptyList(),
                protocol = NeighborProtocol.LLDP,
            )
        }
        return if (warnings.isEmpty()) ParseResult.Success(entries)
        else ParseResult.PartialSuccess(entries, warnings)
    }

    private fun field(block: String, key: String): String? =
        Regex("""(?im)^${Regex.escape(key)}\s*:\s*(.+)$""")
            .find(block)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    private companion object {
        val TOTAL_ZERO = Regex("""(?im)^Total entries displayed\s*:\s*0\s*$""")
    }
}
