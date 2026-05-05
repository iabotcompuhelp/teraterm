package com.opentermx.logger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val ESC = Char(0x1B).toString()
private val CSI = ESC + "["

class AnsiHtmlConverterTest {

    @Test
    fun stripsCsiCursorMovesAndKeepsText() {
        val c = AnsiHtmlConverter()
        val out = c.convertChunk("hi" + CSI + "10A" + "world")
        assertEquals("hiworld", out)
    }

    @Test
    fun convertsBasicForegroundColor() {
        val c = AnsiHtmlConverter()
        val out = c.convertChunk(CSI + "31mROJO" + CSI + "0m") + c.closingTags()
        assertTrue(out.contains("<span style=\"color:#cd0000;\">ROJO</span>")) { "got: $out" }
    }

    @Test
    fun escapesHtmlMetacharacters() {
        val c = AnsiHtmlConverter()
        val out = c.convertChunk("<b>&hi</b>")
        assertEquals("&lt;b&gt;&amp;hi&lt;/b&gt;", out)
    }

    @Test
    fun handles256ColorIndexed() {
        val c = AnsiHtmlConverter()
        val out = c.convertChunk(CSI + "38;5;208mO" + CSI + "0m") + c.closingTags()
        assertTrue(out.contains("rgb(255,135,0)")) { "got: $out" }
    }

    @Test
    fun handlesTruecolor() {
        val c = AnsiHtmlConverter()
        val out = c.convertChunk(CSI + "38;2;100;200;50mX" + CSI + "0m") + c.closingTags()
        assertTrue(out.contains("rgb(100,200,50)")) { "got: $out" }
    }
}