package com.opentermx.netparsers

import java.nio.charset.CodingErrorAction

/**
 * Limpieza del output capturado de un equipo de red antes de devolverlo al cliente MCP
 * o de parsearlo. Cubre los errores #1, #3, #4, #5 y #6 del catálogo:
 *
 *  - decode tolerante de bytes crudos: UTF-8 con reemplazo, NUNCA excepción (equipos
 *    viejos mandan Latin-1 — error #4);
 *  - secuencias ANSI/VT100 (`ESC[...m`, `ESC[K`, ...) y ESC sueltos (error #3);
 *  - backspaces reales: `a\bb` colapsa a `b` (los paginadores "borran" su propio texto,
 *    p. ej. IOS escribe ` --More-- ` + 10 BS + 10 espacios + 10 BS — error #1);
 *  - texto de paginador que quedó incrustado;
 *  - líneas de syslog asíncrono mezcladas en el output (`*Jun  9 ...: %LINK-3-UPDOWN:` —
 *    error #6);
 *  - CRLF/CR normalizados a LF; otros caracteres de control eliminados (se preserva tab);
 *  - eco del comando: si la primera línea con contenido termina con el comando enviado,
 *    se descarta (error #5);
 *  - prompt final: si se pasa [promptRegex], las líneas finales que lo matcheen se
 *    descartan.
 *
 * Vivía en `mcp-server/exec`; se mudó acá (Fase 2) para que los fixtures `_dirty/` lo
 * testeen junto a los parsers y para que `mcp-server` lo reuse vía dependencia.
 */
object OutputCleaner {

    // Construidos con toChar() a propósito: ni escapes ni literales de control en el
    // source — los literales invisibles ya nos rompieron una compilación.
    private val ESC = 27.toChar()
    private val BEL = 7.toChar()

    /** `ESC[...letra` (CSI), `ESC]...BEL` (OSC) y ESC + un char suelto (modos charset, etc.). */
    private val ANSI = Regex(
        Regex.escape(ESC.toString()) + "\\[[0-9;?]*[A-Za-z]" +
            "|" + Regex.escape(ESC.toString()) + "\\][^" + BEL + "]*" + BEL + "?" +
            "|" + Regex.escape(ESC.toString()) + "."
    )

    /** Variantes de paginador conocidas (error #1). Se eliminan del texto, no solo de la línea. */
    private val PAGER_TEXT = Regex(
        """--\s?More\s?--|---- More ----|--More or \(q\)uit--|Press any key to continue([. ]*)?""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Syslog asíncrono intercalado: `*Jun  9 17:42:01.123: %LINK-3-UPDOWN: ...` y
     * variantes sin timestamp (`%LINEPROTO-5-UPDOWN: ...`).
     */
    private val SYSLOG_LINE = Regex(
        """^\s*(\*?\w{3}\s+\d+.*)?%[A-Z0-9_-]+-\d-[A-Z0-9_]+:.*$""",
    )

    /** Bytes crudos del socket → String. UTF-8 con reemplazo; jamás lanza (error #4). */
    fun decode(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

    fun clean(raw: String, command: String? = null, promptRegex: Regex? = null): String {
        var text = raw.replace("\r\n", "\n").replace('\r', '\n')
        text = ANSI.replace(text, "")
        text = applyBackspaces(text)
        text = PAGER_TEXT.replace(text, "")
        // Control chars restantes fuera de \n y \t (BEL, NUL, etc.).
        text = text.filter { it == '\n' || it == '\t' || it.code >= 0x20 }

        var lines = text.split('\n').filterNot { SYSLOG_LINE.matches(it) }
        // Eco del comando: primera línea con contenido que termina con el comando.
        if (command != null) {
            val firstContent = lines.indexOfFirst { it.isNotBlank() }
            if (firstContent >= 0 && lines[firstContent].trimEnd().endsWith(command.trim())) {
                lines = lines.filterIndexed { i, _ -> i != firstContent }
            }
        }
        // Prompt final (y blancos alrededor): no es parte del output del comando.
        if (promptRegex != null) {
            while (lines.isNotEmpty() &&
                (lines.last().isBlank() || promptRegex.containsMatchIn(lines.last().trim()))
            ) {
                lines = lines.dropLast(1)
            }
        }
        return lines.joinToString("\n").trim('\n')
    }

    /** Aplica `\b` destructivamente: borra el carácter anterior si existe en la misma línea. */
    private fun applyBackspaces(text: String): String {
        val bs = 8.toChar()
        if (bs !in text) return text
        val sb = StringBuilder(text.length)
        for (c in text) {
            if (c == bs) {
                if (sb.isNotEmpty() && sb.last() != '\n') sb.deleteCharAt(sb.length - 1)
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }
}
