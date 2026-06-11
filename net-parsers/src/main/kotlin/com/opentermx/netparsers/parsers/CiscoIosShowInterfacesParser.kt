package com.opentermx.netparsers.parsers

import com.opentermx.netparsers.Duplex
import com.opentermx.netparsers.InterfaceStats
import com.opentermx.netparsers.Numbers
import com.opentermx.netparsers.OutputParser
import com.opentermx.netparsers.ParseResult
import com.opentermx.netparsers.PortStatus
import com.opentermx.netparsers.Vendor

/**
 * `show interfaces` de Cisco IOS / IOS-XE clásico. Formato por bloques que arrancan en
 * `<nombre> is <estado>, line protocol is <estado> [(razón)]`.
 *
 * Decisiones alineadas con los fixtures:
 *  - la velocidad sale de la línea de duplex (`Full-duplex, 1000Mb/s`), NO del `BW ...
 *    Kbit/sec` (ese es el bandwidth configurado, presente incluso con el puerto caído);
 *  - `Auto-speed` => speedBps null + duplex AUTO (no inventar la nominal);
 *  - inputDrops = campo `drops` del `Input queue: a/b/drops/d`; outputDrops = `Total
 *    output drops`;
 *  - IOS clásico no reporta last flap => null.
 */
class CiscoIosShowInterfacesParser(
    override val vendor: Vendor = Vendor.CISCO_IOS,
) : OutputParser<List<InterfaceStats>> {

    override val commandPattern = Regex("""^show\s+interfaces?\b.*""", RegexOption.IGNORE_CASE)

    override fun parse(raw: String): ParseResult<List<InterfaceStats>> {
        val blocks = ParserSupport.splitBlocks(raw, HEADER)
        if (blocks.isEmpty()) return ParserSupport.noBlocks("`X is up, line protocol is up`", raw)
        return ParserSupport.success(blocks.map { (header, block) -> parseBlock(header, block) })
    }

    private fun parseBlock(header: MatchResult, block: String): InterfaceStats {
        val (name, adminText, protoText, reason) = header.destructured
        val adminDown = adminText.contains("administratively", ignoreCase = true)
        val oper = when {
            reason.contains("err-disabled", ignoreCase = true) -> PortStatus.ERR_DISABLED
            adminDown -> PortStatus.ADMIN_DOWN
            protoText.equals("up", ignoreCase = true) -> PortStatus.UP
            else -> PortStatus.DOWN
        }
        val duplexSpeed = DUPLEX_SPEED.find(block)
        val inPkts = IN_PACKETS.find(block)
        val outPkts = OUT_PACKETS.find(block)
        val inErr = IN_ERRORS.find(block)
        val outErr = OUT_ERRORS.find(block)
        val raw = buildMap {
            inPkts?.groupValues?.get(2)?.let { put("rxBytes", it) }
            outPkts?.groupValues?.get(2)?.let { put("txBytes", it) }
        }
        return InterfaceStats(
            name = name,
            description = ParserSupport.group1(DESCRIPTION, block),
            adminStatus = if (adminDown) PortStatus.DOWN else PortStatus.UP,
            operStatus = oper,
            speedBps = Numbers.speedToBps(duplexSpeed?.groupValues?.get(2)),
            duplex = Numbers.duplexOf(duplexSpeed?.groupValues?.get(1)) ?: Duplex.UNKNOWN,
            mtu = Numbers.toInt(ParserSupport.group1(MTU, block)),
            inputRateBps = Numbers.toLong(ParserSupport.group1(IN_RATE, block)),
            outputRateBps = Numbers.toLong(ParserSupport.group1(OUT_RATE, block)),
            inputPackets = Numbers.toLong(inPkts?.groupValues?.get(1)),
            outputPackets = Numbers.toLong(outPkts?.groupValues?.get(1)),
            inputErrors = Numbers.toLong(inErr?.groupValues?.get(1)),
            outputErrors = Numbers.toLong(outErr?.groupValues?.get(1)),
            crcErrors = Numbers.toLong(inErr?.groupValues?.get(2)),
            inputDrops = Numbers.toLong(ParserSupport.group1(IN_QUEUE_DROPS, block)),
            outputDrops = Numbers.toLong(ParserSupport.group1(TOTAL_OUT_DROPS, block)),
            collisions = Numbers.toLong(outErr?.groupValues?.get(2)),
            lastFlap = null,
            raw = raw,
        )
    }

    companion object {
        private val HEADER =
            Regex("""^(\S+) is (.+?), line protocol is (\S+)\s*(\(.*\))?\s*$""")
        private val DESCRIPTION = Regex("""^\s*Description:\s*(.+?)\s*$""", RegexOption.MULTILINE)
        private val MTU = Regex("""MTU (\d+) bytes""")
        private val DUPLEX_SPEED =
            Regex("""(Full|Half|Auto)-duplex,?\s*([^,\n]*)""", RegexOption.IGNORE_CASE)
        private val IN_RATE = Regex("""input rate (\d+) bits/sec""")
        private val OUT_RATE = Regex("""output rate (\d+) bits/sec""")
        private val IN_PACKETS = Regex("""(\d+) packets input, (\d+) bytes""")
        private val OUT_PACKETS = Regex("""(\d+) packets output, (\d+) bytes""")
        private val IN_ERRORS = Regex("""(\d+) input errors, (\d+) CRC""")
        private val OUT_ERRORS = Regex("""(\d+) output errors, (\d+) collisions""")
        private val IN_QUEUE_DROPS = Regex("""Input queue: \d+/\d+/(\d+)/\d+""")
        private val TOTAL_OUT_DROPS = Regex("""Total output drops: (\d+)""")
    }
}
