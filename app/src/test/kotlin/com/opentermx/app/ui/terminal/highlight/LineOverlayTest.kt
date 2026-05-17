package com.opentermx.app.ui.terminal.highlight

import com.opentermx.app.ui.terminal.AnsiColor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LineOverlayTest {

    private val red = AnsiColor.Indexed(9)
    private val green = AnsiColor.Indexed(10)

    private fun run(start: Int, end: Int, ruleId: String, fg: AnsiColor = red) =
        OverlayRun(start, end, fg, ruleId = ruleId)

    @Test
    fun `colorAt fuera de rangos devuelve null`() {
        val overlay = LineOverlay(listOf(run(0, 5, "a")))
        assertNull(overlay.colorAt(10))
    }

    @Test
    fun `colorAt dentro del rango devuelve el run`() {
        val overlay = LineOverlay(listOf(run(0, 5, "a")))
        assertEquals("a", overlay.colorAt(2)?.ruleId)
    }

    @Test
    fun `colorAt en endCol no matchea -- exclusivo`() {
        val overlay = LineOverlay(listOf(run(0, 5, "a")))
        assertNull(overlay.colorAt(5))
        assertEquals("a", overlay.colorAt(4)?.ruleId)
    }

    @Test
    fun `colorAt con overlap devuelve el primer match en la lista`() {
        // Ambos cubren col 5; el primero gana (first-match-wins por orden).
        val overlay = LineOverlay(listOf(
            run(0, 10, "first", red),
            run(5, 15, "second", green),
        ))
        assertEquals("first", overlay.colorAt(7)?.ruleId)
    }

    @Test
    fun `EMPTY no tiene runs`() {
        assertEquals(0, LineOverlay.EMPTY.runs.size)
        assertNull(LineOverlay.EMPTY.colorAt(0))
    }
}
