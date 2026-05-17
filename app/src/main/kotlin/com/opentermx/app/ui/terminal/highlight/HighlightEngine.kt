package com.opentermx.app.ui.terminal.highlight

import com.opentermx.app.ui.terminal.TerminalCell
import org.slf4j.LoggerFactory

/**
 * Motor de resaltado per-sesión. Recibe la línea (lista de TerminalCells) más metadata
 * (índice absoluto, si es la cursor row, si el buffer está en alternate screen) y devuelve
 * un [LineOverlay]. NO muta nada.
 *
 * Performance:
 *  - Cache por línea: el último overlay calculado se guarda con la versión del buffer
 *    y el hash del texto plano. Si la versión sube pero el texto de la línea no cambió,
 *    se reutiliza el overlay (típico cuando solo cambia el cursor blink).
 *  - Truncamiento: líneas > [MAX_LINE_CHARS] caracteres se skipean (devuelve EMPTY) para
 *    evitar regex pathológicos sobre payloads enormes (criterio 7.4).
 *  - Soft-timeout: si una regla tarda > [PER_RULE_TIMEOUT_MS] en evaluar UNA línea, se
 *    deshabilita esa regla por [DISABLE_AFTER_TIMEOUT_LINES] líneas siguientes y se loguea.
 *    Java no expone interrupt en `Pattern.matcher` así que esto solo se notifica post-hoc,
 *    NO se interrumpe; el primer hit aún puede congelar 50ms. Mitigación adicional: la
 *    truncation por longitud reduce drásticamente la probabilidad de ReDoS perverso.
 *
 * El engine es thread-confined (debe llamarse desde el FX thread del TerminalView).
 */
class HighlightEngine(
    private val settingsProvider: () -> HighlightSettings,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // Cache: por línea, guardamos (generation, textHash, isCursorRow, overlay). El
    // isCursorRow es parte de la key porque el prompt detection solo se aplica si la fila
    // tiene el cursor — cuando el cursor se mueve a otra fila, la entrada vieja deja de
    // valer (el run del prompt ya no debería estar).
    private data class CacheEntry(
        val generation: Int,
        val textHash: Int,
        val isCursorRow: Boolean,
        val overlay: LineOverlay,
    )
    private val cache = HashMap<Int, CacheEntry>()

    private var compiledRules: List<HighlightRule> = emptyList()
    private var compiledForSettingsHash: Int = -1
    private var generation: Int = 0

    // Ruleset deshabilitado temporalmente por timeout.
    private val ruleDisabledUntilLine = HashMap<String, Int>()
    private var totalLinesProcessed: Int = 0

    /**
     * Calcula (o recupera de cache) el overlay para una línea.
     *
     * @param absRow índice absoluto de la línea en el buffer.
     * @param line cells de la línea.
     * @param isCursorRow `true` si esta es la fila donde está el cursor (candidato a prompt).
     * @param inAlternateScreen `true` si el buffer está en alt-screen mode (vim/top/less).
     */
    fun overlayFor(
        absRow: Int,
        line: List<TerminalCell>,
        isCursorRow: Boolean,
        inAlternateScreen: Boolean,
    ): LineOverlay {
        val settings = settingsProvider()
        if (!settings.enabled) return LineOverlay.EMPTY
        if (inAlternateScreen && settings.skipOnAlternateScreen) return LineOverlay.EMPTY

        ensureCompiled(settings)
        val text = renderToText(line)
        if (text.length > MAX_LINE_CHARS) return LineOverlay.EMPTY

        val cached = cache[absRow]
        val textHash = text.hashCode()
        if (cached != null && cached.generation == generation && cached.textHash == textHash &&
            cached.isCursorRow == isCursorRow
        ) {
            return cached.overlay
        }

        val overlay = compute(text, line, isCursorRow, settings)
        cache[absRow] = CacheEntry(generation, textHash, isCursorRow, overlay)
        totalLinesProcessed++
        return overlay
    }

    private fun compute(
        text: String,
        line: List<TerminalCell>,
        isCursorRow: Boolean,
        settings: HighlightSettings,
    ): LineOverlay {
        val runs = ArrayList<OverlayRun>()

        // 1. Prompt detection — solo en la cursor row.
        if (isCursorRow && settings.promptDetectionEnabled) {
            PromptDetector.detect(text)?.let { det ->
                runs += OverlayRun(
                    startCol = det.startCol,
                    endCol = det.endCol,
                    fg = PromptDetector.colorFor(det.category),
                    mergePolicy = MergePolicy.OVERRIDE,    // el prompt color es lo que importa
                    ruleId = "prompt.${det.category.name}",
                )
            }
        }

        // 2. Reglas de output (built-in + custom). Aplicar en orden de prioridad — runs con
        //    menor priority se agregan PRIMERO y por ende ganan en colorAt (first-match-wins).
        for (rule in compiledRules) {
            if (!rule.enabled) continue
            val disabledUntil = ruleDisabledUntilLine[rule.id] ?: 0
            if (totalLinesProcessed < disabledUntil) continue
            // Skip built-in keywords si el toggle de keywords está apagado.
            val isKeywordCategory = rule.category in keywordCategories
            if (isKeywordCategory && !settings.keywordsEnabled) continue
            // Idem para la categoría de comandos.
            if (rule.category == HighlightCategory.COMMAND && !settings.commandsEnabled) continue

            val started = System.nanoTime()
            try {
                for (match in rule.pattern.findAll(text)) {
                    runs += OverlayRun(
                        startCol = match.range.first,
                        endCol = match.range.last + 1,
                        fg = rule.fg,
                        bg = rule.bg,
                        mergePolicy = rule.mergePolicy,
                        ruleId = rule.id,
                    )
                }
            } catch (t: Throwable) {
                log.warn("Regla '{}' tiró excepción al evaluar — skip: {}", rule.id, t.message)
                ruleDisabledUntilLine[rule.id] = totalLinesProcessed + DISABLE_AFTER_TIMEOUT_LINES
                continue
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            if (elapsedMs > PER_RULE_TIMEOUT_MS) {
                log.warn("Regla '{}' tardó {} ms (límite {} ms) — deshabilitada por {} líneas",
                    rule.id, elapsedMs, PER_RULE_TIMEOUT_MS, DISABLE_AFTER_TIMEOUT_LINES)
                ruleDisabledUntilLine[rule.id] = totalLinesProcessed + DISABLE_AFTER_TIMEOUT_LINES
            }
        }

        return LineOverlay(runs.sortedBy { it.startCol })
    }

    /**
     * El renderer consulta antes de pintar cada celda. Devuelve fg/bg final teniendo en
     * cuenta la merge policy. `serverHasExplicitFg` se computa con `cell.attrs.fg != Default`.
     */
    fun resolveOverlay(
        overlayRun: OverlayRun?,
        serverHasExplicitFg: Boolean,
    ): Pair<com.opentermx.app.ui.terminal.AnsiColor?, com.opentermx.app.ui.terminal.AnsiColor?> {
        if (overlayRun == null) return null to null
        return when (overlayRun.mergePolicy) {
            MergePolicy.RESPECT_SERVER -> {
                if (serverHasExplicitFg) null to overlayRun.bg
                else overlayRun.fg to overlayRun.bg
            }
            MergePolicy.OVERRIDE -> overlayRun.fg to overlayRun.bg
            MergePolicy.MERGE -> null to (overlayRun.bg ?: overlayRun.fg.let { null })
        }
    }

    private fun ensureCompiled(settings: HighlightSettings) {
        val hash = settings.hashCode()
        if (hash == compiledForSettingsHash) return
        val all = ArrayList<HighlightRule>(DefaultHighlightRules.outputRules)
        for (raw in settings.customRules) {
            raw.compile()?.let { all += it }
        }
        // Orden: priority ascendente (menor número primero = mayor prioridad para first-match).
        compiledRules = all.sortedBy { it.priority }
        compiledForSettingsHash = hash
        generation++
        ruleDisabledUntilLine.clear()
        log.debug("HighlightEngine recompiló {} reglas (gen={})", compiledRules.size, generation)
    }

    private fun renderToText(line: List<TerminalCell>): String {
        val sb = StringBuilder(line.size)
        for (cell in line) sb.append(cell.char)
        return sb.toString()
    }

    /** Invalida la cache (ej. al cambiar settings sin pasar por persist). */
    fun invalidateCache() { cache.clear() }

    companion object {
        private const val MAX_LINE_CHARS = 10_000
        private const val PER_RULE_TIMEOUT_MS = 50L
        private const val DISABLE_AFTER_TIMEOUT_LINES = 100

        private val keywordCategories = setOf(
            HighlightCategory.KEYWORD_ERROR,
            HighlightCategory.KEYWORD_WARNING,
            HighlightCategory.KEYWORD_NEGATIVE,
            HighlightCategory.KEYWORD_POSITIVE,
        )
    }
}
