package com.opentermx.mcp.exec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OutputCleanerTest {

    private val esc = 27.toChar()
    private val bs = 8.toChar()
    private val bel = 7.toChar()

    @Test
    fun `quita secuencias ANSI y ESC sueltos`() {
        val raw = "${esc}[32mInterface Up${esc}[0m\n${esc}[KGi0/1 is up"
        assertEquals("Interface Up\nGi0/1 is up", OutputCleaner.clean(raw))
    }

    @Test
    fun `aplica backspaces destructivamente`() {
        // El paginador escribe "--More--" y lo borra con \b al avanzar.
        val raw = "abc${bs}${bs}XY\nlinea"
        assertEquals("aXY\nlinea", OutputCleaner.clean(raw))
    }

    @Test
    fun `elimina texto de paginador`() {
        val raw = "linea 1\n--More--\nlinea 2\n ---- More ---- \nlinea 3 --More or (q)uit--fin"
        val cleaned = OutputCleaner.clean(raw)
        assertEquals(false, cleaned.contains("More"), "quedó paginador en: $cleaned")
    }

    @Test
    fun `normaliza CRLF y CR sueltos a LF`() {
        assertEquals("a\nb\nc", OutputCleaner.clean("a\r\nb\rc"))
    }

    @Test
    fun `remueve el eco del comando`() {
        val raw = "router-1# show version\nCisco IOS Software\nVersion 15.2"
        assertEquals(
            "Cisco IOS Software\nVersion 15.2",
            OutputCleaner.clean(raw, command = "show version"),
        )
    }

    @Test
    fun `sin eco no borra nada`() {
        val raw = "Cisco IOS Software\nVersion 15.2"
        assertEquals(raw, OutputCleaner.clean(raw, command = "show version"))
    }

    @Test
    fun `filtra control chars preservando tab`() {
        val raw = "col1\tcol2${bel}\nfin"
        assertEquals("col1\tcol2\nfin", OutputCleaner.clean(raw))
    }
}
