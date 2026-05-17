package com.opentermx.app.ui.terminal.highlight

import com.fasterxml.jackson.annotation.JsonInclude
import com.opentermx.app.ui.terminal.AnsiColor

/**
 * Categoría semántica de una regla. Usada para agrupar en el editor de UI y para
 * permitir toggle por categoría desde settings.
 */
enum class HighlightCategory {
    PROMPT_USER,        // sufijo `>`
    PROMPT_PRIV,        // sufijo `#` (Cisco enable / root)
    PROMPT_CONFIG,      // sufijo `(config)#`, `(config-if)#`, etc.
    PROMPT_SHELL,       // sufijo `$` (Linux/Unix user)
    KEYWORD_ERROR,      // error, failed, denied, critical, fatal, unreachable
    KEYWORD_WARNING,    // warning, timeout, retry, degraded
    KEYWORD_NEGATIVE,   // down, drop, blocked, disabled
    KEYWORD_POSITIVE,   // up, permit, success, established, enabled
    COMMAND,            // show, configure, interface, ping, shutdown, etc.
    CUSTOM,             // regla agregada por el operador
}

/**
 * Política de fusión cuando una regla matchea una celda que ya tiene color del servidor.
 *  - `RESPECT_SERVER` (default): si la celda tiene `fg != Default`, no aplica la regla.
 *  - `OVERRIDE`: la regla pisa el color del servidor.
 *  - `MERGE`: solo aplica el `bg` de la regla (mantiene `fg` del servidor).
 */
enum class MergePolicy { RESPECT_SERVER, OVERRIDE, MERGE }

/**
 * Regla compilada lista para evaluar. Inmutable, sin dependencias de JavaFX.
 * El `pattern` es un `Regex` con la flag `IGNORE_CASE` por convención (configurable a futuro).
 *
 * `priority` define orden de aplicación cuando hay overlap entre runs: la regla de menor
 * número se evalúa primero y la primera-que-matchea-gana por celda (Fase 1 — Fase 2 expone
 * weighted resolution en la UI).
 */
data class HighlightRule(
    val id: String,
    val name: String,
    val pattern: Regex,
    val fg: AnsiColor,
    val bg: AnsiColor? = null,
    val category: HighlightCategory,
    val mergePolicy: MergePolicy = MergePolicy.RESPECT_SERVER,
    val priority: Int = 100,
    val enabled: Boolean = true,
)

/**
 * Forma serializable de una regla custom para persistir en `HighlightSettings`. La regex
 * va como String (se compila al cargar). Validación de sintaxis en `compile()`.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CustomHighlightRule(
    val id: String,
    val name: String,
    val pattern: String,
    val fgRgb: String,                   // "#RRGGBB"
    val bgRgb: String? = null,
    val mergePolicy: MergePolicy = MergePolicy.RESPECT_SERVER,
    val priority: Int = 200,
    val enabled: Boolean = true,
) {
    fun compile(): HighlightRule? = runCatching {
        HighlightRule(
            id = id,
            name = name,
            pattern = Regex(pattern, RegexOption.IGNORE_CASE),
            fg = hexToAnsi(fgRgb),
            bg = bgRgb?.let { hexToAnsi(it) },
            category = HighlightCategory.CUSTOM,
            mergePolicy = mergePolicy,
            priority = priority,
            enabled = enabled,
        )
    }.getOrNull()

    private fun hexToAnsi(hex: String): AnsiColor {
        val clean = hex.removePrefix("#")
        require(clean.length == 6) { "color hex inválido: $hex" }
        return AnsiColor.Rgb(
            clean.substring(0, 2).toInt(16),
            clean.substring(2, 4).toInt(16),
            clean.substring(4, 6).toInt(16),
        )
    }
}

/**
 * Run de overlay: tramo `[startCol, endCol)` que aplica color al pintar.
 * El renderer consulta `LineOverlay.colorAt(col)` por cada celda visible.
 */
data class OverlayRun(
    val startCol: Int,
    val endCol: Int,             // exclusive
    val fg: AnsiColor,
    val bg: AnsiColor? = null,
    val mergePolicy: MergePolicy = MergePolicy.RESPECT_SERVER,
    val ruleId: String = "",
) {
    fun contains(col: Int): Boolean = col in startCol until endCol
}

/**
 * Overlay precomputado para una línea. `runs` ordenadas por `startCol`; pueden tener overlap
 * pero `colorAt` resuelve "first match wins" (orden = prioridad).
 */
data class LineOverlay(val runs: List<OverlayRun>) {

    fun colorAt(col: Int): OverlayRun? {
        for (run in runs) {
            if (run.contains(col)) return run
        }
        return null
    }

    companion object {
        val EMPTY = LineOverlay(emptyList())
    }
}
