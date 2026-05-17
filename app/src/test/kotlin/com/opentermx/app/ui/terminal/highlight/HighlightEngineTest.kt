package com.opentermx.app.ui.terminal.highlight

import com.opentermx.app.ui.terminal.AnsiColor
import com.opentermx.app.ui.terminal.CellAttributes
import com.opentermx.app.ui.terminal.TerminalCell
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HighlightEngineTest {

    private fun line(text: String, fg: AnsiColor = AnsiColor.Default): List<TerminalCell> =
        text.map { TerminalCell(it, CellAttributes(fg = fg)) }

    private fun engine(settings: HighlightSettings = HighlightSettings()) =
        HighlightEngine(settingsProvider = { settings })

    @Test
    fun `disabled global devuelve EMPTY siempre`() {
        val eng = engine(HighlightSettings(enabled = false))
        val ovr = eng.overlayFor(0, line("Interface FastEthernet0/1 is up"), isCursorRow = false, inAlternateScreen = false)
        assertTrue(ovr.runs.isEmpty())
    }

    @Test
    fun `alternate screen skip respetado`() {
        val eng = engine()
        val ovr = eng.overlayFor(0, line("status: down"), isCursorRow = false, inAlternateScreen = true)
        assertTrue(ovr.runs.isEmpty())
    }

    @Test
    fun `keyword down detectado en linea normal`() {
        val eng = engine()
        val ovr = eng.overlayFor(0, line("Interface Eth0/1 is down"), isCursorRow = false, inAlternateScreen = false)
        assertNotNull(ovr.colorAt(20))   // dentro del rango de "down"
        assertEquals("builtin.negative", ovr.colorAt(20)?.ruleId)
    }

    @Test
    fun `up dispara regla positiva`() {
        val eng = engine()
        val ovr = eng.overlayFor(0, line("Interface Eth0/1 is up"), isCursorRow = false, inAlternateScreen = false)
        assertEquals("builtin.positive", ovr.colorAt(20)?.ruleId)
    }

    @Test
    fun `word boundary evita falso positivo de down en download`() {
        val eng = engine()
        val ovr = eng.overlayFor(0, line("Downloading file..."), isCursorRow = false, inAlternateScreen = false)
        assertNull(ovr.colorAt(5))  // no debería matchear `down` dentro de `Downloading`
    }

    @Test
    fun `error gana sobre warning si ambos matchearan`() {
        val eng = engine()
        val ovr = eng.overlayFor(0, line("error: warning produced"), isCursorRow = false, inAlternateScreen = false)
        // El primer match es "error" (priority 10 < warning 20).
        assertEquals("builtin.error", ovr.colorAt(0)?.ruleId)
    }

    @Test
    fun `prompt detection solo en cursor row`() {
        val eng = engine()
        val text = "Router1#"
        val cells = line(text)
        val notCursor = eng.overlayFor(0, cells, isCursorRow = false, inAlternateScreen = false)
        // En fila no-cursor: prompt no se aplica, pero `#` solo no matchea ninguna keyword regex.
        // El run del prompt no debe estar presente.
        assertNull(notCursor.runs.find { it.ruleId.startsWith("prompt.") })

        val asCursor = eng.overlayFor(0, cells, isCursorRow = true, inAlternateScreen = false)
        assertNotNull(asCursor.runs.find { it.ruleId == "prompt.PROMPT_PRIV" })
    }

    @Test
    fun `linea demasiado larga no se procesa`() {
        val eng = engine()
        val cells = line("error ".repeat(2_000))  // 12k chars > MAX_LINE_CHARS
        val ovr = eng.overlayFor(0, cells, isCursorRow = false, inAlternateScreen = false)
        assertTrue(ovr.runs.isEmpty())
    }

    @Test
    fun `cache reusa overlay cuando texto no cambio`() {
        val eng = engine()
        val cells = line("status: down")
        val first = eng.overlayFor(7, cells, isCursorRow = false, inAlternateScreen = false)
        val second = eng.overlayFor(7, cells, isCursorRow = false, inAlternateScreen = false)
        // Mismo objeto (cache hit) — no garantizado por contrato pero sí por implementación.
        // Lo importante: los runs son consistentes.
        assertEquals(first.runs.map { it.ruleId }, second.runs.map { it.ruleId })
    }

    @Test
    fun `keywords disabled apaga reglas de output`() {
        val eng = engine(HighlightSettings(keywordsEnabled = false))
        val ovr = eng.overlayFor(0, line("status: down"), isCursorRow = false, inAlternateScreen = false)
        assertTrue(ovr.runs.none { it.ruleId.startsWith("builtin.") })
    }

    @Test
    fun `promptDetection disabled no agrega run de prompt`() {
        val eng = engine(HighlightSettings(promptDetectionEnabled = false))
        val ovr = eng.overlayFor(0, line("Router1#"), isCursorRow = true, inAlternateScreen = false)
        assertTrue(ovr.runs.none { it.ruleId.startsWith("prompt.") })
    }

    @Test
    fun `comando show matchea como builtin command`() {
        val eng = engine()
        val ovr = eng.overlayFor(0, line("Router#show ip int brief"), isCursorRow = false, inAlternateScreen = false)
        // "Router#" ocupa cols 0..6; "show" arranca en col 7 y cubre [7, 11).
        val cmdRun = ovr.runs.find { it.ruleId == "builtin.command" }
        assertNotNull(cmdRun)
        assertEquals(7, cmdRun?.startCol)
        assertEquals(11, cmdRun?.endCol)
    }

    @Test
    fun `no shutdown matchea como unidad compuesta`() {
        val eng = engine()
        val ovr = eng.overlayFor(0, line("SW(config-if)#no shutdown"), isCursorRow = false, inAlternateScreen = false)
        val cmdRun = ovr.runs.find { it.ruleId == "builtin.command" }
        assertNotNull(cmdRun)
        // "no shutdown" cubre desde col 14 (después de "SW(config-if)#") por 11 chars.
        assertEquals(14, cmdRun?.startCol)
        assertEquals(25, cmdRun?.endCol)
    }

    @Test
    fun `commandsEnabled false apaga el rule de comandos pero deja keywords`() {
        val eng = engine(HighlightSettings(commandsEnabled = false))
        val ovr = eng.overlayFor(0, line("show ip route — interface up"), isCursorRow = false, inAlternateScreen = false)
        assertNull(ovr.runs.find { it.ruleId == "builtin.command" })
        assertNotNull(ovr.runs.find { it.ruleId == "builtin.positive" })  // "up" sigue prendido
    }

    @Test
    fun `custom rule pathologica con backtracking limita por timeout-or-truncation`() {
        // Esta regex sufre catastrophic backtracking sobre strings de aes seguidos por nada.
        // El engine truncaría líneas > MAX_LINE_CHARS, así que con una entrada típica
        // pequeña el match termina rápido. Verificamos que NO crashea.
        val pathological = CustomHighlightRule(
            id = "test.path",
            name = "pathological",
            pattern = """(a+)+b""",     // típica ReDoS
            fgRgb = "#FF0000",
        )
        val eng = engine(HighlightSettings(customRules = listOf(pathological)))
        val ovr = eng.overlayFor(0, line("aaaaaaaaaaaaaaaaaaaa!"), isCursorRow = false, inAlternateScreen = false)
        // Mientras no haya excepción, el contrato se respeta.
        assertNotNull(ovr)
    }
}
