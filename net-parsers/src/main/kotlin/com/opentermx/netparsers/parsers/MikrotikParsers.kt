package com.opentermx.netparsers.parsers

import com.opentermx.netparsers.Duplex
import com.opentermx.netparsers.InterfaceStats
import com.opentermx.netparsers.Numbers
import com.opentermx.netparsers.OutputParser
import com.opentermx.netparsers.ParseResult
import com.opentermx.netparsers.PortStatus
import com.opentermx.netparsers.Vendor

/**
 * `/interface print stats` de RouterOS 7: tabla alineada por columnas de ancho variable.
 * El parsing es data-driven desde la línea `Columns: NAME, RX-BYTE, ...` — los valores
 * numéricos se toman desde el FINAL de cada fila (el nombre puede no tener flags
 * adelante, así que contar desde el principio es frágil).
 *
 * Flags: `X` = disabled (admin down), `R` = running. `TX-QUEUE-DROP` se ignora a
 * propósito: el canónico `outputDrops` es TX-DROP.
 */
class MikrotikPrintStatsParser : OutputParser<List<InterfaceStats>> {

    override val vendor = Vendor.MIKROTIK
    override val commandPattern =
        Regex("""^/?interface\s+print\s+stats\b.*""", RegexOption.IGNORE_CASE)

    override fun parse(raw: String): ParseResult<List<InterfaceStats>> {
        val lines = raw.replace("\r\n", "\n").split('\n')
        val columnsLine = lines.firstOrNull { it.trimStart().startsWith("Columns:") }
            ?: return ParserSupport.noBlocks("línea `Columns: NAME, ...` (formato ROS7)", raw)
        val columns = columnsLine.substringAfter("Columns:").split(',').map { it.trim() }
        val nameIdx = columns.indexOf("NAME")
        if (nameIdx != 0 || columns.size < 2) {
            return ParserSupport.noBlocks("columna NAME al inicio de `Columns:`", raw)
        }
        val numericCount = columns.size - 1

        val interfaces = lines.mapNotNull { line ->
            val m = ROW.find(line) ?: return@mapNotNull null
            val tokens = m.groupValues[1].trim().split(Regex("\\s+"))
            if (tokens.size < numericCount + 1) return@mapNotNull null
            val name = tokens[tokens.size - numericCount - 1]
            val flags = tokens.subList(0, tokens.size - numericCount - 1).joinToString("")
            val numbers = tokens.takeLast(numericCount)
            val byColumn = columns.drop(1).zip(numbers).toMap()

            val disabled = flags.contains('X')
            InterfaceStats(
                name = name,
                adminStatus = if (disabled) PortStatus.DOWN else PortStatus.UP,
                operStatus = when {
                    disabled -> PortStatus.ADMIN_DOWN
                    flags.contains('R') -> PortStatus.UP
                    else -> PortStatus.DOWN
                },
                inputPackets = Numbers.toLong(byColumn["RX-PACKET"]),
                outputPackets = Numbers.toLong(byColumn["TX-PACKET"]),
                inputErrors = Numbers.toLong(byColumn["RX-ERROR"]),
                outputErrors = Numbers.toLong(byColumn["TX-ERROR"]),
                inputDrops = Numbers.toLong(byColumn["RX-DROP"]),
                outputDrops = Numbers.toLong(byColumn["TX-DROP"]),
                raw = buildMap {
                    byColumn["RX-BYTE"]?.let { put("rxBytes", it) }
                    byColumn["TX-BYTE"]?.let { put("txBytes", it) }
                },
            )
        }
        if (interfaces.isEmpty()) return ParserSupport.noBlocks("filas de datos de la tabla", raw)
        return ParseResult.PartialSuccess(interfaces, listOf(NO_RATES_WARNING))
    }

    companion object {
        const val NO_RATES_WARNING =
            "RouterOS print stats no expone tasas bps ni CRC por separado; usar " +
                "/interface monitor-traffic para tasas y ethernet monitor para link"

        /** Fila de datos: índice numérico al inicio; el resto se tokeniza. */
        private val ROW = Regex("""^\s*\d+\s+(.+)$""")
    }
}

/**
 * `/interface ethernet monitor <if> once` de RouterOS: pares `clave: valor` indentados.
 * Trae estado de link, velocidad negociada y duplex — sin contadores.
 */
class MikrotikEthernetMonitorParser : OutputParser<List<InterfaceStats>> {

    override val vendor = Vendor.MIKROTIK
    override val commandPattern =
        Regex("""^/?interface\s+ethernet\s+monitor\b.*""", RegexOption.IGNORE_CASE)

    override fun parse(raw: String): ParseResult<List<InterfaceStats>> {
        val kv = KV.findAll(raw.replace("\r\n", "\n"))
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
        val name = kv["name"] ?: return ParserSupport.noBlocks("par `name: <if>`", raw)
        val linkOk = kv["status"].equals("link-ok", ignoreCase = true)
        val stats = InterfaceStats(
            name = name,
            adminStatus = PortStatus.UP,
            operStatus = if (linkOk) PortStatus.UP else PortStatus.DOWN,
            speedBps = Numbers.speedToBps(kv["rate"]),
            duplex = when (kv["full-duplex"]?.lowercase()) {
                "yes" -> Duplex.FULL
                "no" -> Duplex.HALF
                else -> null
            },
        )
        return ParseResult.Success(listOf(stats))
    }

    companion object {
        private val KV = Regex("""^\s*([a-z][a-z0-9\-]*):\s*(.*)$""", RegexOption.MULTILINE)
    }
}
