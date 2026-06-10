package com.opentermx.app.ui

import com.opentermx.app.settings.AppSettings
import com.opentermx.app.ui.terminal.TerminalView

/**
 * Aplica settings de apariencia/comportamiento a todos los [TerminalView] vivos:
 * fuente, colores, scrollback, terminal, teclado, additional y cursor de mouse.
 * Extraído de `MainWindow` (split 2026-06) — esta clase es puramente "fan-out":
 * no persiste nada; la persistencia queda en quien dispara el cambio.
 *
 * [allTerminals] debe devolver la lista actual en cada invocación (los tabs
 * cambian); puede contener duplicados, las aplicaciones son idempotentes.
 */
class TerminalSettingsApplier(
    private val settings: () -> AppSettings,
    private val theme: Theme,
    private val allTerminals: () -> List<TerminalView>,
) {

    fun applyFont() {
        val s = settings()
        forEach { it.applyFont(s.terminalFontFamily, s.terminalFontSize) }
    }

    fun applyScrollback(limit: Int) {
        forEach { it.applyScrollbackLimit(limit) }
    }

    fun applyTheme() {
        val c = effectiveTerminalColors()
        forEach { it.applyColors(c.foreground, c.background, c.cursor, c.selection) }
    }

    fun applyTerminalSettings(t: com.opentermx.app.settings.TerminalSettings) {
        forEach {
            it.applyTerminalSettings(
                cursorStyle = t.cursorStyle,
                cursorBlink = t.cursorBlink,
                encoding = t.encoding,
                newlineMode = t.newlineMode,
                localEcho = t.localEcho,
                scrollMode = t.scrollMode,
            )
        }
    }

    fun applyKeyboardSettings(k: com.opentermx.app.settings.KeyboardSettings) {
        forEach {
            it.applyKeyboardSettings(
                backspaceSendsDel = k.backspaceSendsDel,
                deleteSendsBs = k.deleteSendsBs,
                metaSendsEscape = k.metaSendsEscape,
            )
        }
    }

    fun applyAdditionalSettings(a: com.opentermx.app.settings.AdditionalSettings) {
        forEach {
            it.applyAdditionalSettings(
                copyOnSelect = a.copyOnSelect,
                visualCursorBlink = a.visualCursorBlink,
                blinkText = a.blinkText,
            )
        }
        // Phase 2.5 T3: el TelnetConnection lee esta system property en cada `connect()`
        // para decidir si registra el spy stream de IAC. Sesiones ya abiertas no se ven
        // afectadas — el flag aplica desde la próxima conexión.
        System.setProperty("opentermx.telnet.verboseLog", a.telnetVerboseLog.toString())
    }

    fun applyMouseCursor(mode: String) {
        forEach { it.applyMouseCursor(mode) }
    }

    /**
     * Resolves the effective terminal palette: theme provides cursor/selection (and the fg/bg
     * defaults), while the user's `WindowSettings.terminalForeground`/`terminalBackground`
     * overrides win for fg/bg when present. A blank override means "follow the theme".
     */
    fun effectiveTerminalColors(): TerminalColors {
        val themeColors = theme.terminalColors
        val w = settings().window
        val fg = parseHex(w.terminalForeground) ?: themeColors.foreground
        val bg = parseHex(w.terminalBackground) ?: themeColors.background
        return TerminalColors(fg, bg, themeColors.cursor, themeColors.selection)
    }

    private fun parseHex(hex: String): javafx.scene.paint.Color? =
        if (hex.isBlank()) null else runCatching { javafx.scene.paint.Color.web(hex) }.getOrNull()

    private inline fun forEach(action: (TerminalView) -> Unit) {
        allTerminals().forEach(action)
    }
}
