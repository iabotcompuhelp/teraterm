package com.opentermx.app.ui.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val ESC = Char(0x1B).toString()
private val CSI = ESC + "["
private val OSC = ESC + "]"
private val BEL = Char(0x07).toString()

class AnsiParserTest {

    private fun emu(cols: Int = 20, rows: Int = 5): Pair<TerminalBuffer, TerminalEmulator> {
        val buf = TerminalBuffer(cols, rows)
        return buf to TerminalEmulator(buf)
    }

    @Test
    fun `prints plain text and advances cursor`() {
        val (buf, e) = emu()
        e.feed("hello")
        assertEquals('h', buf.cellAt(buf.visibleTop, 0).char)
        assertEquals('o', buf.cellAt(buf.visibleTop, 4).char)
        assertEquals(5, buf.cursorCol)
    }

    @Test
    fun `SGR sets basic foreground color`() {
        val (buf, e) = emu()
        e.feed(CSI + "31mR")
        assertEquals(AnsiColor.Indexed(1), buf.cellAt(buf.visibleTop, 0).attrs.fg)
    }

    @Test
    fun `SGR parses 256-color indexed`() {
        val (buf, e) = emu()
        e.feed(CSI + "38;5;208mO")
        assertEquals(AnsiColor.Indexed(208), buf.cellAt(buf.visibleTop, 0).attrs.fg)
    }

    @Test
    fun `SGR parses truecolor RGB`() {
        val (buf, e) = emu()
        e.feed(CSI + "38;2;100;200;50mX")
        assertEquals(AnsiColor.Rgb(100, 200, 50), buf.cellAt(buf.visibleTop, 0).attrs.fg)
    }

    @Test
    fun `SGR bold and reset`() {
        val (buf, e) = emu()
        e.feed(CSI + "1mB" + CSI + "0mN")
        assertTrue(buf.cellAt(buf.visibleTop, 0).attrs.bold)
        assertFalse(buf.cellAt(buf.visibleTop, 1).attrs.bold)
    }

    @Test
    fun `CUP positions cursor 1-based`() {
        val (buf, e) = emu()
        e.feed(CSI + "3;7H")
        assertEquals(buf.visibleTop + 2, buf.cursorRow)
        assertEquals(6, buf.cursorCol)
    }

    @Test
    fun `erase in display clears all`() {
        val (buf, e) = emu(cols = 10, rows = 3)
        e.feed("aaaaa" + CSI + "2J")
        for (col in 0 until 10) {
            assertEquals(' ', buf.cellAt(buf.visibleTop, col).char)
        }
    }

    @Test
    fun `OSC 0 sets window title via BEL terminator`() {
        val (buf, e) = emu()
        e.feed(OSC + "0;My Title" + BEL)
        assertEquals("My Title", buf.windowTitle)
    }

    @Test
    fun `private mode 1049 toggles alternate screen`() {
        val (buf, e) = emu()
        e.feed("main")
        e.feed(CSI + "?1049h")
        assertTrue(buf.alternateMode)
        e.feed(CSI + "?1049l")
        assertFalse(buf.alternateMode)
        assertEquals('m', buf.cellAt(buf.visibleTop, 0).char)
    }

    @Test
    fun `CR returns to column zero`() {
        val (buf, e) = emu()
        e.feed("hello\rX")
        assertEquals('X', buf.cellAt(buf.visibleTop, 0).char)
    }

    @Test
    fun `private mode 25 toggles cursor visibility`() {
        val (buf, e) = emu()
        e.feed(CSI + "?25l")
        assertFalse(buf.cursorVisible)
        e.feed(CSI + "?25h")
        assertTrue(buf.cursorVisible)
    }

    @Test
    fun `LF advances cursor row`() {
        val (buf, e) = emu()
        val startRow = buf.cursorRow
        e.feed("a\nb")
        assertEquals(startRow + 1, buf.cursorRow)
    }
}