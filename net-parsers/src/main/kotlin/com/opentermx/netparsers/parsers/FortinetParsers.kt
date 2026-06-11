package com.opentermx.netparsers.parsers

import com.opentermx.netparsers.InterfaceStats
import com.opentermx.netparsers.Numbers
import com.opentermx.netparsers.OutputParser
import com.opentermx.netparsers.ParseResult
import com.opentermx.netparsers.PortStatus
import com.opentermx.netparsers.Vendor

/**
 * `get system interface` de FortiOS: una entrada `== [ nombre ]` por interfaz seguida de
 * una línea clave: valor separada por espacios múltiples. SOLO trae estado lógico —
 * ni contadores ni velocidad — así que el resultado es [ParseResult.PartialSuccess]
 * con un warning que apunta a `diagnose hardware deviceinfo nic <port>`.
 */
class FortinetGetSystemInterfaceParser : OutputParser<List<InterfaceStats>> {

    override val vendor = Vendor.FORTINET
    override val commandPattern = Regex("""^get\s+system\s+interface\b.*""", RegexOption.IGNORE_CASE)

    override fun parse(raw: String): ParseResult<List<InterfaceStats>> {
        val blocks = ParserSupport.splitBlocks(raw, HEADER)
        if (blocks.isEmpty()) return ParserSupport.noBlocks("entradas `== [ nombre ]`", raw)
        val interfaces = blocks.map { (header, block) ->
            InterfaceStats(
                name = header.groupValues[1],
                description = ParserSupport.group1(DESCRIPTION, block),
                // FortiOS no expone admin state acá: status es el estado del link.
                adminStatus = PortStatus.UP,
                operStatus = if (ParserSupport.group1(STATUS, block).equals("up", ignoreCase = true))
                    PortStatus.UP else PortStatus.DOWN,
                mtu = Numbers.toInt(ParserSupport.group1(MTU, block)),
            )
        }
        return ParseResult.PartialSuccess(interfaces, listOf(NO_COUNTERS_WARNING))
    }

    companion object {
        const val NO_COUNTERS_WARNING =
            "get system interface no expone contadores ni velocidad; combinar con " +
                "diagnose hardware deviceinfo nic <port> para metricas"

        private val HEADER = Regex("""^== \[ (\S+) \]\s*$""")
        private val STATUS = Regex("""\bstatus:\s*(\S+)""")
        private val DESCRIPTION = Regex("""\bdescription:\s*(.+?)(?:\s{2,}|\s*$)""")
        // `mtu:` exacto — no confundir con `mtu-override:`.
        private val MTU = Regex("""(?<![\w-])mtu:\s*(\d+)""")
    }
}

/**
 * `diagnose hardware deviceinfo nic <port>` de FortiOS: pares `Clave: valor` + sección
 * de contadores estilo driver Linux (`rx_packets`, `rx_crc_errors`, ...). Una sola
 * interfaz por invocación. `Speed: 10000full` lleva la velocidad y el duplex pegados.
 */
class FortinetDiagNicParser : OutputParser<List<InterfaceStats>> {

    override val vendor = Vendor.FORTINET
    override val commandPattern =
        Regex("""^diagnose\s+hardware\s+deviceinfo\s+nic\b.*""", RegexOption.IGNORE_CASE)

    override fun parse(raw: String): ParseResult<List<InterfaceStats>> {
        val name = ParserSupport.group1(NAME, raw)
            ?: return ParserSupport.noBlocks("línea `Name: <port>`", raw)
        val speedToken = ParserSupport.group1(SPEED, raw)
        val rxBytes = counter(raw, "rx_bytes")
        val txBytes = counter(raw, "tx_bytes")
        val stats = InterfaceStats(
            name = name,
            description = null,
            adminStatus = if (ParserSupport.group1(STATE, raw).equals("up", ignoreCase = true))
                PortStatus.UP else PortStatus.DOWN,
            operStatus = if (ParserSupport.group1(LINK, raw).equals("up", ignoreCase = true))
                PortStatus.UP else PortStatus.DOWN,
            speedBps = Numbers.speedToBps(speedToken),
            duplex = Numbers.duplexOf(speedToken),
            mtu = Numbers.toInt(ParserSupport.group1(MTU, raw)),
            inputPackets = counter(raw, "rx_packets"),
            outputPackets = counter(raw, "tx_packets"),
            inputErrors = counter(raw, "rx_errors"),
            outputErrors = counter(raw, "tx_errors"),
            crcErrors = counter(raw, "rx_crc_errors"),
            inputDrops = counter(raw, "rx_dropped"),
            outputDrops = counter(raw, "tx_dropped"),
            collisions = counter(raw, "collisions"),
            raw = buildMap {
                rxBytes?.let { put("rxBytes", it.toString()) }
                txBytes?.let { put("txBytes", it.toString()) }
            },
        )
        return ParseResult.Success(listOf(stats))
    }

    private fun counter(raw: String, key: String): Long? =
        Numbers.toLong(Regex("""^$key:\s*(\d+)""", RegexOption.MULTILINE).find(raw)?.groupValues?.get(1))

    companion object {
        private val NAME = Regex("""^Name:\s*(\S+)""", RegexOption.MULTILINE)
        private val STATE = Regex("""^State:\s*(\S+)""", RegexOption.MULTILINE)
        private val LINK = Regex("""^Link:\s*(\S+)""", RegexOption.MULTILINE)
        private val MTU = Regex("""^Mtu:\s*(\d+)""", RegexOption.MULTILINE)
        private val SPEED = Regex("""^Speed:\s*(\S+)""", RegexOption.MULTILINE)
    }
}
