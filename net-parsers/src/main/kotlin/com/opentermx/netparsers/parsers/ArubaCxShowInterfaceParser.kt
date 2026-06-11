package com.opentermx.netparsers.parsers

import com.opentermx.netparsers.InterfaceStats
import com.opentermx.netparsers.Numbers
import com.opentermx.netparsers.OutputParser
import com.opentermx.netparsers.ParseResult
import com.opentermx.netparsers.PortStatus
import com.opentermx.netparsers.Vendor

/**
 * `show interface` de Aruba AOS-CX. Bloques que arrancan en `Interface <n> is up|down [(razón)]`.
 * Las tasas salen de las secciones `Rate RX` / `Rate TX` (`Total (bps)`); el lastFlap es la
 * frase completa de `Link state:` (`up for 3 weeks (since ...)`) — se guarda cruda.
 */
class ArubaCxShowInterfaceParser : OutputParser<List<InterfaceStats>> {

    override val vendor = Vendor.ARUBA_AOSCX
    override val commandPattern = Regex("""^show\s+interface\b.*""", RegexOption.IGNORE_CASE)

    override fun parse(raw: String): ParseResult<List<InterfaceStats>> {
        val blocks = ParserSupport.splitBlocks(raw, HEADER)
        if (blocks.isEmpty()) return ParserSupport.noBlocks("`Interface X is up|down`", raw)
        return ParserSupport.success(blocks.map { (header, block) -> parseBlock(header, block) })
    }

    private fun parseBlock(header: MatchResult, block: String): InterfaceStats {
        val name = header.groupValues[1]
        val linkState = header.groupValues[2]
        val adminDown = ParserSupport.group1(ADMIN_STATE, block)?.equals("down", ignoreCase = true) == true
        val oper = when {
            adminDown -> PortStatus.ADMIN_DOWN
            linkState.equals("up", ignoreCase = true) -> PortStatus.UP
            else -> PortStatus.DOWN
        }
        // Rate RX aparece antes que Rate TX: primer Total (bps) = entrada, segundo = salida.
        val rates = TOTAL_BPS.findAll(block).map { it.groupValues[1] }.toList()
        val inErr = IN_ERR_DROP.find(block)
        val outErr = OUT_ERR_DROP.find(block)
        val inPkts = IN_PACKETS.find(block)
        val outPkts = OUT_PACKETS.find(block)
        val raw = buildMap {
            inPkts?.groupValues?.get(2)?.let { put("rxBytes", it) }
            outPkts?.groupValues?.get(2)?.let { put("txBytes", it) }
        }
        return InterfaceStats(
            name = name,
            description = ParserSupport.group1(DESCRIPTION, block),
            adminStatus = if (adminDown) PortStatus.DOWN else PortStatus.UP,
            operStatus = oper,
            speedBps = Numbers.speedToBps(ParserSupport.group1(SPEED, block)),
            duplex = Numbers.duplexOf(ParserSupport.group1(DUPLEX, block)),
            mtu = Numbers.toInt(ParserSupport.group1(MTU, block)),
            inputRateBps = Numbers.toLong(rates.getOrNull(0)),
            outputRateBps = Numbers.toLong(rates.getOrNull(1)),
            inputPackets = Numbers.toLong(inPkts?.groupValues?.get(1)),
            outputPackets = Numbers.toLong(outPkts?.groupValues?.get(1)),
            inputErrors = Numbers.toLong(inErr?.groupValues?.get(1)),
            outputErrors = Numbers.toLong(outErr?.groupValues?.get(1)),
            crcErrors = Numbers.toLong(ParserSupport.group1(CRC_FCS, block)),
            inputDrops = Numbers.toLong(inErr?.groupValues?.get(2)),
            outputDrops = Numbers.toLong(outErr?.groupValues?.get(2)),
            collisions = Numbers.toLong(ParserSupport.group1(COLLISIONS, block)),
            lastFlap = ParserSupport.group1(LINK_STATE, block),
            raw = raw,
        )
    }

    companion object {
        private val HEADER = Regex("""^Interface (\S+) is (\S+)(?:\s*\((.+?)\))?\s*$""")
        private val ADMIN_STATE = Regex("""Admin state is (\S+)""")
        private val LINK_STATE = Regex("""Link state:\s*(.+?)\s*$""", RegexOption.MULTILINE)
        private val DESCRIPTION = Regex("""^\s*Description:\s*(.+?)\s*$""", RegexOption.MULTILINE)
        private val MTU = Regex("""^\s*MTU (\d+)""", RegexOption.MULTILINE)
        private val SPEED = Regex("""^\s*Speed (\d+\s*\S+)""", RegexOption.MULTILINE)
        private val DUPLEX = Regex("""(Full|Half|Auto)-[Dd]uplex""")
        private val TOTAL_BPS = Regex("""Total \(bps\)\s+(\d+)""")
        private val IN_PACKETS = Regex("""(\d+) input packets\s+(\d+) bytes""")
        private val OUT_PACKETS = Regex("""(\d+) output packets\s+(\d+) bytes""")
        private val IN_ERR_DROP = Regex("""(\d+) input errors?\s+(\d+) dropped""")
        private val OUT_ERR_DROP = Regex("""(\d+) output errors?\s+(\d+) dropped""")
        private val CRC_FCS = Regex("""(\d+) CRC/FCS""")
        private val COLLISIONS = Regex("""(\d+) collision""")
    }
}
