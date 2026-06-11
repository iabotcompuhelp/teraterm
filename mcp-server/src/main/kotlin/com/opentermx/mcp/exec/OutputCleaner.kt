package com.opentermx.mcp.exec

/**
 * Limpieza del output capturado de un equipo de red antes de devolverlo al cliente MCP
 * (y, en Fase 2, antes de parsearlo). Cubre los errores #1, #3, #4 y #5 del catálogo:
 *
 *  - secuencias ANSI/VT100 (`ESC[...m`, `ESC[K`, ...) y ESC sueltos;
 *  - backspaces reales: `a\bb` colapsa a `b` (los paginadores "borran" su propio texto así);
 *  - texto del paginador (`--More--` y variantes) que quedó incrustado;
 *  - CRLF/CR normalizados a LF; otros caracteres de control eliminados (se preserva tab);
 *  - eco del comando: si la primera línea con contenido termina con el comando enviado,
 *    se descarta.
 *
 * Vive en `mcp-server` para la Fase 1; cuando exista `net-parsers` (Fase 2) los fixtures
 * `_dirty/` lo ejercitan y puede mudarse a un módulo compartido.
 */
object OutputCleaner {

    // Construidos con toChar() a propósito: ni escapes \-u ni literales de control en el
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

    fun clean(raw: String, command: String? = null): String {
        var text = raw.replace("\r\n", "\n").replace('\r', '\n')
        text = ANSI.replace(text, "")
        text = applyBackspaces(text)
        text = PAGER_TEXT.replace(text, "")
        // Control chars restantes fuera de \n y \t (BEL, NUL, etc.).
        text = text.filter { it == '\n' || it == '\t' || it.code >= 0x20 }

        var lines = text.split('\n')
        // Eco del comando: primera línea con contenido que termina con el comando.
        if (command != null) {
            val firstContent = lines.indexOfFirst { it.isNotBlank() }
            if (firstContent >= 0 && lines[firstContent].trimEnd().endsWith(command.trim())) {
                lines = lines.filterIndexed { i, _ -> i != firstContent }
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
