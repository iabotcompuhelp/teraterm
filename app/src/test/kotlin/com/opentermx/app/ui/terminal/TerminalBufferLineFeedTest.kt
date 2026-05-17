package com.opentermx.app.ui.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regresión: cuando el cursor estaba en la última fila visible (scrollBottomVis) y entraba
 * un `\n`, llamábamos `scrollUp` (que agrega una blank line al final del deque) pero NO
 * avanzábamos `cursorRow`. Resultado visible: el cursor "subía" una fila respecto del
 * fondo, y los próximos chars del servidor (prompt nuevo, output del comando, etc.) se
 * escribían encima del contenido anterior — prompt doblado en la misma línea, líneas
 * superpuestas.
 *
 * El fix: en `lineFeed`, cuando estamos en scrollBottomVis de la región completa
 * (no alternate mode) y scrollUp efectivamente creció `lines.size` (no truncó scrollback),
 * avanzamos cursorRow por 1 para que quede sobre la nueva blank line al fondo.
 */
class TerminalBufferLineFeedTest {

    private fun feed(buffer: TerminalBuffer, text: String) {
        for (c in text) {
            when (c) {
                '\r' -> buffer.carriageReturn()
                '\n' -> buffer.lineFeed()
                else -> buffer.putChar(c)
            }
        }
    }

    @Test
    fun `enter en el bottom avanza el cursor a la nueva linea blank al hacer scrollUp`() {
        val buf = TerminalBuffer(initialCols = 80, initialRows = 4, scrollbackLimit = 100)
        // Llenamos las 4 filas: cursor termina en row 3 (scrollBottomVis).
        feed(buf, "a\nb\nc\nd")
        assertEquals(3, buf.cursorRow)
        assertEquals(0, buf.visibleTop)

        // Enter en el bottom: \n debería scrollear y avanzar el cursor.
        buf.lineFeed()

        // Tras scrollUp: lines.size creció a 5, visibleTop = 1, cursor debería estar en
        // la NUEVA blank line (row 4, visRow = 3 = scrollBottomVis).
        assertEquals(5, buf.totalLines)
        assertEquals(1, buf.visibleTop)
        assertEquals(4, buf.cursorRow)
        assertEquals(3, buf.cursorRow - buf.visibleTop)  // visRow = scrollBottomVis ✓
    }

    @Test
    fun `prompt repetido tras Enter no se sobreescribe sobre la fila previa`() {
        val buf = TerminalBuffer(initialCols = 80, initialRows = 4, scrollbackLimit = 100)
        // Simulamos un terminal en bottom row con prompt "P# " y el cursor justo después
        // del prompt. ENTER (CR+LF) + nuevo prompt debe dejar el primero intacto y el
        // segundo en la nueva línea de abajo.
        feed(buf, "\r\n\r\n\r\nP# ")  // 3 \r\n llevan el cursor a row 3; luego "P# ".
        assertEquals(3, buf.cursorRow)

        // \r\n + nuevo prompt
        feed(buf, "\r\nP# ")

        // El primer prompt debe estar en row 3, el segundo en row 4.
        val line3 = (0 until 3).joinToString("") { buf.cellAt(3, it).char.toString() }
        val line4 = (0 until 3).joinToString("") { buf.cellAt(4, it).char.toString() }
        assertEquals("P# ", line3)
        assertEquals("P# ", line4)
    }

    @Test
    fun `simulacion completa del bug 50 enters con prompt repetido en bottom`() {
        // Simulacion del escenario del usuario: window de 45 filas, conecta, presiona
        // Enter muchas veces. El server responde con `\r\n + prompt` para cada uno.
        // Tras ~45 enters el cursor llega a scrollBottomVis y los enters siguientes
        // disparan scrollUp + mi fix. Cada prompt debe quedar en su propia fila.
        val buf = TerminalBuffer(initialCols = 80, initialRows = 45, scrollbackLimit = 1_000)
        val prompt = "d0:4d:c6:c6:8c:86# "

        // 50 ciclos de "\r\n + prompt" — 5 disparan scrollUp.
        repeat(50) {
            feed(buf, "\r\n$prompt")
        }

        // Las últimas 45 filas visibles (rows [visibleTop, lines.size)) deberían contener
        // un prompt cada una — la última con el cursor justo después.
        val visibleTopExpected = buf.totalLines - 45
        for (visRow in 0 until 45) {
            val absRow = visibleTopExpected + visRow
            val line = (0 until prompt.length).joinToString("") { buf.cellAt(absRow, it).char.toString() }
            assertEquals(prompt, line, "Fila visible $visRow (absRow=$absRow) debería tener el prompt completo")
        }
    }

    @Test
    fun `repro exacto del trace HPE Aruba — dos prompts por Enter en filas separadas`() {
        // Replica byte-a-byte el comportamiento del switch HPE/Aruba observado en
        // [vt-trace]: cada Enter del usuario genera del server dos chunks separados —
        //  1) "\r\n"  (2 bytes)
        //  2) "prompt + \r\n + prompt"  (40 bytes)
        // Esos dos chunks producen DOS prompts nuevos en filas separadas. Si la
        // emulación falla, los dos prompts caen en la misma fila.
        val emu = TerminalEmulator(TerminalBuffer(initialCols = 80, initialRows = 24, scrollbackLimit = 1000))
        val buf = emu.buffer
        val prompt = "d0:4d:c6:c6:8c:86# "

        // Banner inicial + prompt inicial (similar a lo que sale en line 80 del trace).
        emu.feed("\r\nbanner text\r\n\r\n$prompt")
        val initialCursorRow = buf.cursorRow

        // Primer Enter — dos chunks separados (replicando líneas 81 y 82 del trace).
        emu.feed("\r\n")
        emu.feed("$prompt\r\n$prompt")

        // Resultado esperado: tres filas consecutivas con el prompt
        //  - initialCursorRow:           prompt original (sin tocar)
        //  - initialCursorRow + 1:       primer prompt nuevo
        //  - initialCursorRow + 2:       segundo prompt nuevo
        for (i in 0..2) {
            val absRow = initialCursorRow + i
            val line = (0 until prompt.length).joinToString("") { buf.cellAt(absRow, it).char.toString() }
            assertEquals(prompt, line, "Fila $absRow (offset $i) debería tener el prompt completo")
        }
    }

    @Test
    fun `lineFeed lejos del bottom solo avanza cursor sin crear linea`() {
        val buf = TerminalBuffer(initialCols = 80, initialRows = 10, scrollbackLimit = 100)
        feed(buf, "x")
        val sizeBefore = buf.totalLines
        buf.lineFeed()
        assertEquals(1, buf.cursorRow)
        // No scrollUp porque cursorRow (1) != scrollBottomVis (9). lines.size puede crecer
        // por ensureLine pero no debería triplicarse.
        assertEquals(sizeBefore.coerceAtLeast(2), buf.totalLines)
    }
}
