package com.opentermx.fingerprint.neighbors

import com.opentermx.netparsers.OutputParser
import com.opentermx.netparsers.ParseResult
import com.opentermx.netparsers.Vendor

/**
 * `display lldp neighbor` de VRP: secciones por interfaz local
 * (`GigabitEthernet0/0/1 has 1 neighbor(s):`) con bloques `Neighbor index :N` adentro.
 */
class HuaweiLldpNeighborParser : OutputParser<List<NeighborEntry>> {

    override val vendor = Vendor.HUAWEI_VRP
    override val commandPattern = Regex("""^display\s+lldp\s+neighbor$""", RegexOption.IGNORE_CASE)

    override fun parse(raw: String): ParseResult<List<NeighborEntry>> {
        val sections = SECTION_HEADER.findAll(raw).toList()
        if (sections.isEmpty()) {
            return ParseResult.Failure("sin secciones `<interfaz> has N neighbor(s)` de LLDP VRP", raw.take(300))
        }
        val warnings = mutableListOf<String>()
        val entries = mutableListOf<NeighborEntry>()
        sections.forEachIndexed { i, header ->
            val local = header.groupValues[1]
            val sectionEnd = sections.getOrNull(i + 1)?.range?.first ?: raw.length
            val body = raw.substring(header.range.last + 1, sectionEnd)
            body.split(Regex("""(?im)^Neighbor index\s*:""")).drop(1).forEach { block ->
                val name = field(block, "System name")
                if (name == null) {
                    warnings += "vecino LLDP en `$local` sin System name — descartado"
                    return@forEach
                }
                entries += NeighborEntry(
                    localInterface = local,
                    remoteHostname = name,
                    remotePort = field(block, "Port ID"),
                    capabilities = field(block, "System capabilities enabled")
                        ?.split(Regex("""\s+"""))?.filter { it.isNotEmpty() }
                        ?: emptyList(),
                    protocol = NeighborProtocol.LLDP,
                )
            }
        }
        return if (warnings.isEmpty()) ParseResult.Success(entries)
        else ParseResult.PartialSuccess(entries, warnings)
    }

    /** VRP alinea con espacios ANTES de los dos puntos: `System name         :CE-AGG-01`. */
    private fun field(block: String, key: String): String? =
        Regex("""(?im)^${Regex.escape(key)}\s*:\s*(.+)$""")
            .find(block)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    private companion object {
        val SECTION_HEADER = Regex("""(?im)^(\S+)\s+has\s+\d+\s+neighbor\(s\):\s*$""")
    }
}
