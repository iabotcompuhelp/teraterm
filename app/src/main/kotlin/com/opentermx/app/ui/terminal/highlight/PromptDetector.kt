package com.opentermx.app.ui.terminal.highlight

import com.opentermx.app.ui.terminal.AnsiColor

/**
 * Heurística pura: dada una línea de texto plano (sin ANSI), determina si termina en un
 * prompt conocido y devuelve la categoría + la columna donde empieza el "sufijo" que hay
 * que colorear.
 *
 * El renderer luego pinta `[suffixStart, suffixEnd)` con el color asociado a la categoría.
 *
 * Patrones reconocidos (orden importa — el primero que matchea gana):
 *  - `(config-*)#` o `(config)#`            → PROMPT_CONFIG
 *  - termina en `#` (Cisco enable / root)    → PROMPT_PRIV
 *  - termina en `$`                          → PROMPT_SHELL
 *  - termina en `>` (Cisco user EXEC)        → PROMPT_USER
 *  - termina en `%` (csh/zsh)                → PROMPT_SHELL
 *  - termina en `]` con `~]$` o `~]#`        → hereda del char antes del `]`
 *
 * No matchea prompts custom con emojis o multi-line por ahora (Fase 2: regex configurable
 * con named group `mode`).
 *
 * **No usar en líneas que no sean la "última activa"**: solo se aplica donde está parado
 * el cursor (en espera de input). El caller (HighlightEngine) filtra por cursor row.
 */
object PromptDetector {

    /** Resultado: categoría detectada + rango (inclusive, exclusivo) del sufijo a colorear. */
    data class Detection(
        val category: HighlightCategory,
        val startCol: Int,
        val endCol: Int,
    )

    fun detect(line: String): Detection? {
        // Recortamos espacios finales antes del cursor — el sufijo es el último char "tipográfico".
        val trimEnd = line.indexOfLast { !it.isWhitespace() }
        if (trimEnd < 0) return null

        val last = line[trimEnd]

        // 1. `(config*)#` → PROMPT_CONFIG. Buscar el `)` justo antes del `#`.
        if (last == '#') {
            val maybeParen = trimEnd - 1
            if (maybeParen >= 0 && line[maybeParen] == ')') {
                // Buscar el `(config` matching hacia atrás.
                val openIdx = line.lastIndexOf("(config", maybeParen)
                if (openIdx >= 0) {
                    return Detection(HighlightCategory.PROMPT_CONFIG, openIdx, trimEnd + 1)
                }
            }
            return Detection(HighlightCategory.PROMPT_PRIV, trimEnd, trimEnd + 1)
        }
        if (last == '$') return Detection(HighlightCategory.PROMPT_SHELL, trimEnd, trimEnd + 1)
        if (last == '%') return Detection(HighlightCategory.PROMPT_SHELL, trimEnd, trimEnd + 1)
        if (last == '>') return Detection(HighlightCategory.PROMPT_USER, trimEnd, trimEnd + 1)

        // `~]$` o `~]#` (bash con CWD entre []) — el sufijo real es el char después del `]`.
        // Si llegamos acá con `]` como último, no es un prompt activo (el último char no es uno).
        return null
    }

    fun colorFor(category: HighlightCategory): AnsiColor =
        DefaultHighlightRules.promptColors[category] ?: AnsiColor.Default
}
