package com.opentermx.app.ui.terminal.highlight

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PromptDetectorTest {

    @Test
    fun `Cisco user EXEC termina en mayor que`() {
        val det = PromptDetector.detect("Router1>")
        assertEquals(HighlightCategory.PROMPT_USER, det?.category)
        assertEquals(7, det?.startCol)
        assertEquals(8, det?.endCol)
    }

    @Test
    fun `Cisco enable termina en numeral`() {
        val det = PromptDetector.detect("Router1#")
        assertEquals(HighlightCategory.PROMPT_PRIV, det?.category)
        assertEquals(7, det?.startCol)
    }

    @Test
    fun `Cisco config mode matchea desde el parentesis`() {
        val det = PromptDetector.detect("SW-CORE(config)#")
        assertEquals(HighlightCategory.PROMPT_CONFIG, det?.category)
        // El run cubre `(config)#` — desde la `(` hasta el `#` inclusive.
        assertEquals(7, det?.startCol)
        assertEquals(16, det?.endCol)
    }

    @Test
    fun `Cisco config-if matchea como CONFIG`() {
        val det = PromptDetector.detect("SW-CORE(config-if)#")
        assertEquals(HighlightCategory.PROMPT_CONFIG, det?.category)
        assertEquals(7, det?.startCol)
    }

    @Test
    fun `Cisco config-vlan matchea como CONFIG`() {
        val det = PromptDetector.detect("SW(config-vlan)#")
        assertEquals(HighlightCategory.PROMPT_CONFIG, det?.category)
    }

    @Test
    fun `Linux user shell termina en dolar`() {
        val det = PromptDetector.detect("user@host:~$")
        assertEquals(HighlightCategory.PROMPT_SHELL, det?.category)
    }

    @Test
    fun `Linux root termina en numeral`() {
        val det = PromptDetector.detect("root@host:/etc#")
        assertEquals(HighlightCategory.PROMPT_PRIV, det?.category)
    }

    @Test
    fun `zsh con porcentaje`() {
        val det = PromptDetector.detect("user@mac:~%")
        assertEquals(HighlightCategory.PROMPT_SHELL, det?.category)
    }

    @Test
    fun `Juniper operational mode termina en mayor que con espacio`() {
        // JunOS muestra `user@junos> ` con trailing space; el detector recorta espacios.
        val det = PromptDetector.detect("operator@junos> ")
        assertEquals(HighlightCategory.PROMPT_USER, det?.category)
    }

    @Test
    fun `FortiOS termina en numeral`() {
        val det = PromptDetector.detect("FGT-100E #")
        assertEquals(HighlightCategory.PROMPT_PRIV, det?.category)
    }

    @Test
    fun `MikroTik prompt RouterOS termina en mayor que`() {
        val det = PromptDetector.detect("[admin@MikroTik] >")
        assertEquals(HighlightCategory.PROMPT_USER, det?.category)
    }

    @Test
    fun `Windows cmd termina en mayor que`() {
        val det = PromptDetector.detect("C:\\Users\\admin>")
        assertEquals(HighlightCategory.PROMPT_USER, det?.category)
    }

    @Test
    fun `linea sin prompt devuelve null`() {
        assertNull(PromptDetector.detect("plain text line without prompt char"))
    }

    @Test
    fun `linea vacia devuelve null`() {
        assertNull(PromptDetector.detect(""))
    }

    @Test
    fun `solo whitespace devuelve null`() {
        assertNull(PromptDetector.detect("    "))
    }
}
