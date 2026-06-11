package com.opentermx.netparsers.parsers

import com.opentermx.netparsers.InterfaceStats
import com.opentermx.netparsers.ParseResult

/** Helpers compartidos por los parsers. Todo devuelve null ante no-match: nunca lanzar. */
internal object ParserSupport {

    /** Primer grupo de captura del primer match, o null. */
    fun group1(regex: Regex, text: String): String? = regex.find(text)?.groupValues?.get(1)

    /**
     * Particiona [raw] en bloques: cada línea que matchea [header] abre un bloque que
     * incluye todo hasta el siguiente header. Las líneas previas al primer header
     * (banners, basura) se descartan. Devuelve pares (matchDelHeader, textoDelBloque).
     */
    fun splitBlocks(raw: String, header: Regex): List<Pair<MatchResult, String>> {
        val lines = raw.replace("\r\n", "\n").split('\n')
        val starts = lines.withIndex().mapNotNull { (i, line) ->
            header.find(line)?.let { i to it }
        }
        return starts.mapIndexed { idx, (lineIdx, match) ->
            val end = if (idx + 1 < starts.size) starts[idx + 1].first else lines.size
            match to lines.subList(lineIdx, end).joinToString("\n")
        }
    }

    /** Failure estándar con muestra de 500 chars para diagnóstico (regla de oro Fase 2). */
    fun noBlocks(expected: String, raw: String): ParseResult.Failure =
        ParseResult.Failure(
            reason = "el output no contiene bloques reconocibles ($expected)",
            rawSample = raw.take(500),
        )

    /** Línea de syslog asíncrono o comentario que un parser debe ignorar (error #6). */
    fun isNoise(line: String): Boolean =
        line.trimStart().startsWith("%") ||
            Regex("""^\*?\w{3}\s+\d+ .*%[A-Z0-9_-]+-\d-""").containsMatchIn(line)

    fun success(interfaces: List<InterfaceStats>): ParseResult<List<InterfaceStats>> =
        ParseResult.Success(interfaces)
}
