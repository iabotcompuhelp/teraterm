package com.opentermx.fingerprint.neighbors

import com.opentermx.netparsers.OutputParser
import com.opentermx.netparsers.ParseResult
import com.opentermx.netparsers.Vendor

/**
 * `/ip neighbor print detail` de RouterOS: registros `N key=value ...` con continuación
 * indentada y valores entre comillas. MNDP no anuncia puerto remoto — queda null.
 */
class MikrotikIpNeighborParser : OutputParser<List<NeighborEntry>> {

    override val vendor = Vendor.MIKROTIK
    override val commandPattern =
        Regex("""^/ip\s+neighbor\s+print\s+detail\b.*$""", RegexOption.IGNORE_CASE)

    override fun parse(raw: String): ParseResult<List<NeighborEntry>> {
        // Re-arma registros: una línea `^ N ...` abre registro; las indentadas continúan.
        val records = mutableListOf<StringBuilder>()
        raw.lineSequence().forEach { line ->
            when {
                RECORD_START.containsMatchIn(line) -> records += StringBuilder(line)
                records.isNotEmpty() && line.startsWith(" ") -> records.last().append(' ').append(line.trim())
            }
        }
        if (records.isEmpty()) {
            return ParseResult.Failure("sin registros `N interface=...` de /ip neighbor", raw.take(300))
        }
        val warnings = mutableListOf<String>()
        val entries = records.mapNotNull { record ->
            val text = record.toString()
            val iface = value(text, "interface")
            val identity = value(text, "identity")
            if (iface == null || identity == null) {
                warnings += "registro de /ip neighbor sin interface o identity — descartado"
                return@mapNotNull null
            }
            NeighborEntry(
                localInterface = iface,
                remoteHostname = identity,
                remotePort = null,
                protocol = NeighborProtocol.MNDP,
            )
        }
        return if (warnings.isEmpty()) ParseResult.Success(entries)
        else ParseResult.PartialSuccess(entries, warnings)
    }

    /** `key="valor con espacios"` o `key=valor` (hasta el próximo espacio). */
    private fun value(record: String, key: String): String? {
        val quoted = Regex("""\b${Regex.escape(key)}="([^"]*)"""").find(record)
        if (quoted != null) return quoted.groupValues[1].ifEmpty { null }
        return Regex("""\b${Regex.escape(key)}=(\S+)""").find(record)
            ?.groupValues?.get(1)?.ifEmpty { null }
    }

    private companion object {
        /** `interface=` distingue un registro de neighbor de cualquier otra tabla print. */
        val RECORD_START = Regex("""^\s*\d+\s+.*\binterface=""")
    }
}
