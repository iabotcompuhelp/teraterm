package com.opentermx.netparsers.parsers

import com.opentermx.netparsers.Duplex
import com.opentermx.netparsers.InterfaceStats
import com.opentermx.netparsers.Numbers
import com.opentermx.netparsers.OutputParser
import com.opentermx.netparsers.ParseResult
import com.opentermx.netparsers.PortStatus
import com.opentermx.netparsers.Vendor

/**
 * `show interface` de Cisco NX-OS. Bloques que arrancan en `<nombre> is up|down [(razón)]`
 * con `admin state is up|down` en la línea siguiente. Los contadores viven en secciones
 * RX/TX (`input discard`/`output discard` son los drops). La tasa reportada es la del
 * primer Load-Interval (30 s en los fixtures) — es la más fresca.
 */
class NxosShowInterfaceParser : OutputParser<List<InterfaceStats>> {

    override val vendor = Vendor.CISCO_NXOS
    override val commandPattern = Regex("""^show\s+interface\b.*""", RegexOption.IGNORE_CASE)

    override fun parse(raw: String): ParseResult<List<InterfaceStats>> {
        val blocks = ParserSupport.splitBlocks(raw, HEADER)
        if (blocks.isEmpty()) return ParserSupport.noBlocks("`X is up` con `admin state is ...`", raw)
        return ParserSupport.success(blocks.map { (header, block) -> parseBlock(header, block) })
    }

    private fun parseBlock(header: MatchResult, block: String): InterfaceStats {
        val name = header.groupValues[1]
        val linkState = header.groupValues[2]
        val reason = header.groupValues[3]
        val adminDown = reason.contains("administratively", ignoreCase = true) ||
            ParserSupport.group1(ADMIN_STATE, block)?.equals("down", ignoreCase = true) == true
        val oper = when {
            reason.contains("err", ignoreCase = true) && reason.contains("disabled", ignoreCase = true) ->
                PortStatus.ERR_DISABLED
            adminDown -> PortStatus.ADMIN_DOWN
            linkState.equals("up", ignoreCase = true) -> PortStatus.UP
            else -> PortStatus.DOWN
        }
        val duplexSpeed = DUPLEX_SPEED.find(block)
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
            speedBps = Numbers.speedToBps(duplexSpeed?.groupValues?.get(2)),
            duplex = Numbers.duplexOf(duplexSpeed?.groupValues?.get(1)) ?: Duplex.UNKNOWN,
            mtu = Numbers.toInt(ParserSupport.group1(MTU, block)),
            inputRateBps = Numbers.toLong(ParserSupport.group1(IN_RATE, block)),
            outputRateBps = Numbers.toLong(ParserSupport.group1(OUT_RATE, block)),
            inputPackets = Numbers.toLong(inPkts?.groupValues?.get(1)),
            outputPackets = Numbers.toLong(outPkts?.groupValues?.get(1)),
            inputErrors = Numbers.toLong(ParserSupport.group1(IN_ERRORS, block)),
            outputErrors = Numbers.toLong(ParserSupport.group1(OUT_ERRORS, block)),
            crcErrors = Numbers.toLong(ParserSupport.group1(CRC, block)),
            inputDrops = Numbers.toLong(ParserSupport.group1(IN_DISCARD, block)),
            outputDrops = Numbers.toLong(ParserSupport.group1(OUT_DISCARD, block)),
            collisions = Numbers.toLong(ParserSupport.group1(COLLISIONS, block)),
            lastFlap = ParserSupport.group1(LAST_FLAP, block),
            raw = raw,
        )
    }

    companion object {
        // No matchea el formato IOS (`..., line protocol is ...`) gracias al $ tras la razón.
        private val HEADER = Regex("""^(\S+) is (up|down)(?:\s*\((.+?)\))?\s*$""")
        private val ADMIN_STATE = Regex("""admin state is (\S+?),?\s""")
        private val DESCRIPTION = Regex("""^\s*Description:\s*(.+?)\s*$""", RegexOption.MULTILINE)
        private val MTU = Regex("""MTU (\d+) bytes""")
        private val DUPLEX_SPEED =
            Regex("""(full|half|auto)-duplex,\s*([^,\n]*)""", RegexOption.IGNORE_CASE)
        private val IN_RATE = Regex("""input rate (\d+) bits/sec""")
        private val OUT_RATE = Regex("""output rate (\d+) bits/sec""")
        private val IN_PACKETS = Regex("""(\d+) input packets\s+(\d+) bytes""")
        private val OUT_PACKETS = Regex("""(\d+) output packets\s+(\d+) bytes""")
        private val IN_ERRORS = Regex("""(\d+) input error\b""")
        private val OUT_ERRORS = Regex("""(\d+) output error\b""")
        private val CRC = Regex("""(\d+) CRC\b""")
        private val IN_DISCARD = Regex("""(\d+) input discard""")
        private val OUT_DISCARD = Regex("""(\d+) output discard""")
        private val COLLISIONS = Regex("""(\d+) collision\b""")
        private val LAST_FLAP = Regex("""Last link flapped\s+(.+?)\s*$""", RegexOption.MULTILINE)
    }
}
