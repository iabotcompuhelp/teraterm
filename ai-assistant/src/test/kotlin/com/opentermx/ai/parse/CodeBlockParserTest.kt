package com.opentermx.ai.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodeBlockParserTest {

    @Test
    fun parsesSingleCiscoBlockWithFollowingExplanation() {
        val response = """
            Voy a configurar la interfaz como pediste:

            ```cisco
            configure terminal
            interface GigabitEthernet0/1
             ip address 10.0.0.1 255.255.255.0
             no shutdown
             exit
            end
            write memory
            ```

            Este bloque entra en modo configuración, asigna la IP /24 y guarda.
        """.trimIndent()

        val blocks = CodeBlockParser.parse(response)
        assertEquals(1, blocks.size)
        val b = blocks[0]
        assertEquals("cisco", b.language)
        assertEquals(7, b.lines.size)
        assertEquals("configure terminal", b.lines[0])
        assertEquals(" ip address 10.0.0.1 255.255.255.0", b.lines[2])
        assertTrue(b.explanation.contains("Voy a configurar"))
        assertTrue(b.explanation.contains("Este bloque entra"))
    }

    @Test
    fun parsesMultipleBlocks() {
        val response = """
            Primero el show:

            ```
            show ip interface brief
            ```

            Luego el cambio:

            ```cisco
            configure terminal
            no ip address
            end
            ```
        """.trimIndent()
        val blocks = CodeBlockParser.parse(response)
        assertEquals(2, blocks.size)
        assertEquals("", blocks[0].language)
        assertEquals(listOf("show ip interface brief"), blocks[0].lines)
        assertEquals("cisco", blocks[1].language)
        assertEquals(3, blocks[1].lines.size)
    }

    @Test
    fun returnsEmptyWhenNoFencedBlock() {
        val response = "No es necesario ningún cambio. Tu config ya está bien."
        assertEquals(0, CodeBlockParser.parse(response).size)
    }

    @Test
    fun returnsEmptyWhenUnpairedFence() {
        val response = "```cisco\nshow version"
        assertEquals(0, CodeBlockParser.parse(response).size)
    }

    @Test
    fun narrativeOnlyStripsBlocks() {
        val response = """
            Primero ejecuta:
            ```bash
            ls -la
            ```
            Y luego revisa el output.
        """.trimIndent()
        val narrative = CodeBlockParser.narrativeOnly(response)
        assertTrue(narrative.contains("Primero ejecuta"))
        assertTrue(narrative.contains("revisa el output"))
        assertTrue("ls -la" !in narrative)
    }
}
