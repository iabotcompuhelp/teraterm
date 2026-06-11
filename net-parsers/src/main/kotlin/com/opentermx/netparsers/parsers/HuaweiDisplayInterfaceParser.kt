package com.opentermx.netparsers.parsers

import com.opentermx.netparsers.InterfaceStats
import com.opentermx.netparsers.Numbers
import com.opentermx.netparsers.OutputParser
import com.opentermx.netparsers.ParseResult
import com.opentermx.netparsers.PortStatus
import com.opentermx.netparsers.Vendor

/**
 * `display interface` de Huawei VRP. Bloques que arrancan en `<nombre> current state : X`.
 *
 * Decisiones alineadas con los fixtures:
 *  - VRP reporta la velocidad CONFIGURADA aun con el puerto admin-down (`Speed : 1000`)
 *    => se conserva, a diferencia del Auto-speed de Cisco;
 *  - `The Maximum Frame Length is N` NO es el MTU L3 => mtu siempre null acá;
 *  - los contadores viven en bloques `Input:` / `Output:` con claves repetidas
 *    (`Discard`, `Total Error`) => se parsean POR SECCIÓN, no global;
 *  - lastFlap = `Last physical up time` crudo (timestamp absoluto del equipo).
 */
class HuaweiDisplayInterfaceParser : OutputParser<List<InterfaceStats>> {

    override val vendor = Vendor.HUAWEI_VRP
    override val commandPattern = Regex("""^display\s+interface\b.*""", RegexOption.IGNORE_CASE)

    override fun parse(raw: String): ParseResult<List<InterfaceStats>> {
        val blocks = ParserSupport.splitBlocks(raw, HEADER)
        if (blocks.isEmpty()) return ParserSupport.noBlocks("`X current state : ...`", raw)
        return ParserSupport.success(blocks.map { (header, block) -> parseBlock(header, block) })
    }

    private fun parseBlock(header: MatchResult, block: String): InterfaceStats {
        val name = header.groupValues[1]
        val state = header.groupValues[2]
        val adminDown = state.contains("administratively", ignoreCase = true)
        val oper = when {
            adminDown -> PortStatus.ADMIN_DOWN
            ParserSupport.group1(LINE_PROTOCOL, block)?.equals("UP", ignoreCase = true) == true ->
                PortStatus.UP
            else -> PortStatus.DOWN
        }

        // Secciones Input:/Output: — claves repetidas entre ambas.
        val inputStart = block.indexOf("\nInput:")
        val outputStart = block.indexOf("\nOutput:")
        val inputSection = if (inputStart >= 0)
            block.substring(inputStart, if (outputStart > inputStart) outputStart else block.length)
        else ""
        val outputSection = if (outputStart >= 0) block.substring(outputStart) else ""

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
            mtu = null,
            inputRateBps = Numbers.toLong(ParserSupport.group1(IN_RATE, block)),
            outputRateBps = Numbers.toLong(ParserSupport.group1(OUT_RATE, block)),
            inputPackets = Numbers.toLong(inPkts?.groupValues?.get(1)),
            outputPackets = Numbers.toLong(outPkts?.groupValues?.get(1)),
            inputErrors = Numbers.toLong(ParserSupport.group1(TOTAL_ERROR, inputSection)),
            outputErrors = Numbers.toLong(ParserSupport.group1(TOTAL_ERROR, outputSection)),
            crcErrors = Numbers.toLong(ParserSupport.group1(CRC, inputSection)),
            inputDrops = Numbers.toLong(ParserSupport.group1(DISCARD, inputSection)),
            outputDrops = Numbers.toLong(ParserSupport.group1(DISCARD, outputSection)),
            collisions = Numbers.toLong(ParserSupport.group1(COLLISIONS, outputSection)),
            lastFlap = ParserSupport.group1(LAST_UP_TIME, block),
            raw = raw,
        )
    }

    companion object {
        private val HEADER = Regex("""^(\S+) current state : (.+?)\s*$""")
        private val LINE_PROTOCOL = Regex("""Line protocol current state : (\S+)""")
        private val DESCRIPTION = Regex("""^Description:\s*(.+?)\s*$""", RegexOption.MULTILINE)
        private val SPEED = Regex("""Speed\s*:\s*(\d+)""")
        private val DUPLEX = Regex("""Duplex:\s*([A-Za-z]+)""")
        private val IN_RATE = Regex("""input rate (\d+) bits/sec""")
        private val OUT_RATE = Regex("""output rate (\d+) bits/sec""")
        private val IN_PACKETS = Regex("""Input:\s*(\d+) packets, (\d+) bytes""")
        private val OUT_PACKETS = Regex("""Output:\s*(\d+) packets, (\d+) bytes""")
        private val DISCARD = Regex("""Discard:\s*(\d+)""")
        private val TOTAL_ERROR = Regex("""Total Error:\s*(\d+)""")
        private val CRC = Regex("""CRC:\s*(\d+)""")
        private val COLLISIONS = Regex("""Collisions:\s*(\d+)""")
        private val LAST_UP_TIME = Regex("""Last physical up time\s*:\s*(.+?)\s*$""", RegexOption.MULTILINE)
    }
}
