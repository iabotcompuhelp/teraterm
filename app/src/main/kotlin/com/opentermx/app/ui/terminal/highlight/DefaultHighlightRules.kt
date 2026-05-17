package com.opentermx.app.ui.terminal.highlight

import com.opentermx.app.ui.terminal.AnsiColor

/**
 * Reglas built-in del MVP. Usan `AnsiColor.Indexed` para que el operador pueda re-estilar
 * vía tema (los Indexed van por la palette del renderer). Si en Fase 2 agregamos themes
 * para daltónicos / alto-contraste, basta intercambiar la palette.
 *
 * Códigos indexed (palette estándar XTERM):
 *  - 1=red, 2=green, 3=yellow, 6=cyan
 *  - 9=red bright, 10=green bright, 11=yellow bright
 */
object DefaultHighlightRules {

    /** Categorías de output (siempre evaluadas en runs de texto del servidor). */
    val outputRules: List<HighlightRule> = listOf(
        // Errores: prioridad alta (gana sobre warning si llegara a haber overlap).
        rule(
            id = "builtin.error",
            name = "Errores",
            pattern = """\b(error|errors|failed|failure|denied|critical|fatal|unreachable|invalid)\b""",
            fg = AnsiColor.Indexed(9),   // red bright
            category = HighlightCategory.KEYWORD_ERROR,
            priority = 10,
        ),
        rule(
            id = "builtin.warning",
            name = "Advertencias",
            pattern = """\b(warning|warn|timeout|retry|retries|degraded|missed)\b""",
            fg = AnsiColor.Indexed(11),  // yellow bright
            category = HighlightCategory.KEYWORD_WARNING,
            priority = 20,
        ),
        rule(
            id = "builtin.negative",
            name = "Estado negativo",
            pattern = """\b(down|drop|dropped|blocked|disabled|inactive|reject|rejected)\b""",
            fg = AnsiColor.Indexed(1),   // red dim
            category = HighlightCategory.KEYWORD_NEGATIVE,
            priority = 30,
        ),
        rule(
            id = "builtin.positive",
            name = "Estado positivo",
            // `up` con \b también matchearía "Up" o "UP". Excluimos prefijos comunes
            // como "Upgrade", "Uptime" via negative lookbehind aproximado: usamos \b por ahora.
            pattern = """\b(up|permit|permitted|success|successful|established|enabled|active|ok)\b""",
            fg = AnsiColor.Indexed(10),  // green bright
            category = HighlightCategory.KEYWORD_POSITIVE,
            priority = 40,
        ),
        // Comandos: tanto los que el operador tipea (eco local + server echo) como los que
        // aparecen en historial / running-config. Alternativas longest-first para que
        // `no shutdown` matchee como unidad antes que `shutdown` solo.
        rule(
            id = "builtin.command",
            name = "Comandos",
            pattern = """\b(no\s+shutdown|configure\s+terminal|show|display|configure|interface|shutdown|ping|traceroute|commit|save|reboot|reload|write|copy|erase|delete|enable|disable|exit|end|conf\s+t|conf\s+term)\b""",
            fg = AnsiColor.Indexed(14),  // cyan bright
            category = HighlightCategory.COMMAND,
            priority = 50,
        ),
    )

    /** Colores sugeridos por tipo de prompt (consumidos por PromptDetector). */
    val promptColors: Map<HighlightCategory, AnsiColor> = mapOf(
        HighlightCategory.PROMPT_USER to AnsiColor.Indexed(10),    // verde
        HighlightCategory.PROMPT_PRIV to AnsiColor.Indexed(9),     // rojo bright
        HighlightCategory.PROMPT_CONFIG to AnsiColor.Indexed(11),  // amarillo bright
        HighlightCategory.PROMPT_SHELL to AnsiColor.Indexed(14),   // cyan bright
    )

    private fun rule(
        id: String,
        name: String,
        pattern: String,
        fg: AnsiColor,
        category: HighlightCategory,
        priority: Int,
    ): HighlightRule = HighlightRule(
        id = id,
        name = name,
        pattern = Regex(pattern, setOf(RegexOption.IGNORE_CASE)),
        fg = fg,
        category = category,
        priority = priority,
        mergePolicy = MergePolicy.RESPECT_SERVER,
    )
}
