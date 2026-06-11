package com.opentermx.fingerprint.neighbors

import com.opentermx.netparsers.OutputParser
import com.opentermx.netparsers.ParseResult
import com.opentermx.netparsers.Vendor

/**
 * `show cdp neighbors detail` (IOS/IOS-XE/NX-OS): bloques separados por `Device ID:`.
 * Solo se extrae lo que la topología necesita — interfaz local, vecino, puerto remoto
 * y capacidades anunciadas.
 */
class CiscoCdpNeighborsDetailParser(
    override val vendor: Vendor = Vendor.CISCO_IOS,
) : OutputParser<List<NeighborEntry>> {

    override val commandPattern = Regex("""^show\s+cdp\s+neighbors\s+detail$""", RegexOption.IGNORE_CASE)

    override fun parse(raw: String): ParseResult<List<NeighborEntry>> {
        if (!raw.contains("Device ID:")) {
            if (TOTAL_ZERO.containsMatchIn(raw)) return ParseResult.Success(emptyList())
            return ParseResult.Failure("sin bloques `Device ID:` de CDP", raw.take(300))
        }
        val warnings = mutableListOf<String>()
        val entries = raw.split("Device ID:").drop(1).mapNotNull { block ->
            val deviceId = block.lineSequence().first().trim().ifEmpty { null }
            val ifaces = INTERFACE_LINE.find(block)
            if (deviceId == null || ifaces == null) {
                warnings += "bloque CDP sin Device ID o sin línea Interface — descartado"
                return@mapNotNull null
            }
            NeighborEntry(
                localInterface = ifaces.groupValues[1],
                remoteHostname = deviceId,
                remotePort = ifaces.groupValues[2].ifEmpty { null },
                capabilities = CAPABILITIES.find(block)?.groupValues?.get(1)
                    ?.trim()?.split(Regex("""\s+"""))?.filter { it.isNotEmpty() }
                    ?: emptyList(),
                protocol = NeighborProtocol.CDP,
            )
        }
        return if (warnings.isEmpty()) ParseResult.Success(entries)
        else ParseResult.PartialSuccess(entries, warnings)
    }

    private companion object {
        val INTERFACE_LINE =
            Regex("""(?im)^Interface:\s*(\S+?),\s*Port ID \(outgoing port\):\s*(\S*)""")
        val CAPABILITIES = Regex("""(?im)\bCapabilities:\s*(.+)$""")
        val TOTAL_ZERO = Regex("""(?im)^Total cdp entries displayed\s*:\s*0\s*$""")
    }
}
