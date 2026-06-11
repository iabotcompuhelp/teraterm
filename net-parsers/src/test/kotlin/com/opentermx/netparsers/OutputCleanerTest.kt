package com.opentermx.netparsers

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutputCleanerTest {

    private val esc = 27.toChar()
    private val bs = 8.toChar()
    private val bel = 7.toChar()

    private val genericPrompt = Regex("""^[<\[]?[\w.\-]+[>\]#$]\s*$""")

    // ------------------------------------------------------------- unit básicos

    @Test
    fun `quita secuencias ANSI y ESC sueltos`() {
        val raw = "${esc}[32mInterface Up${esc}[0m\n${esc}[KGi0/1 is up"
        assertEquals("Interface Up\nGi0/1 is up", OutputCleaner.clean(raw))
    }

    @Test
    fun `aplica backspaces destructivamente`() {
        val raw = "abc${bs}${bs}XY\nlinea"
        assertEquals("aXY\nlinea", OutputCleaner.clean(raw))
    }

    @Test
    fun `elimina texto de paginador`() {
        val raw = "linea 1\n--More--\nlinea 2\n ---- More ---- \nlinea 3 --More or (q)uit--fin"
        val cleaned = OutputCleaner.clean(raw)
        assertFalse(cleaned.contains("More"), "quedó paginador en: $cleaned")
    }

    @Test
    fun `normaliza CRLF y CR sueltos a LF y filtra control chars`() {
        assertEquals("a\nb\nc", OutputCleaner.clean("a\r\nb\rc"))
        assertEquals("col1\tcol2\nfin", OutputCleaner.clean("col1\tcol2${bel}\nfin"))
    }

    @Test
    fun `remueve el eco del comando y el prompt final`() {
        val raw = "router-1# show version\nCisco IOS Software\nVersion 15.2\nrouter-1#"
        assertEquals(
            "Cisco IOS Software\nVersion 15.2",
            OutputCleaner.clean(raw, command = "show version", promptRegex = genericPrompt),
        )
    }

    @Test
    fun `descarta lineas de syslog asincrono`() {
        val raw = "linea 1\n*Jun  9 17:42:01.123: %LINK-3-UPDOWN: Interface Gi0/2, changed state to up\nlinea 2"
        assertEquals("linea 1\nlinea 2", OutputCleaner.clean(raw))
    }

    // ------------------------------------------------------------- fixtures _dirty

    @Test
    fun `dirty cisco — pager con backspaces, ANSI, syslog, CRLF y prompt final`() {
        val root = FixtureParserTest.fixturesRoot().resolve("_dirty")
        val rawBytes = Files.readAllBytes(root.resolve("cisco_ios_raw_pager_ansi_syslog.bin.txt"))
        val expected = Files.readString(root.resolve("cisco_ios_raw_pager_ansi_syslog.cleaned.txt"))
            .replace("\r\n", "\n").trimEnd('\n')

        val cleaned = OutputCleaner.clean(
            OutputCleaner.decode(rawBytes),
            command = "show interfaces GigabitEthernet0/1",
            promptRegex = genericPrompt,
        )
        assertEquals(expected, cleaned)
    }

    @Test
    fun `dirty huawei — Latin-1 invalido no lanza, paginador y prompt desaparecen`() {
        val root = FixtureParserTest.fixturesRoot().resolve("_dirty")
        val rawBytes = Files.readAllBytes(root.resolve("huawei_raw_latin1_more.bin.txt"))

        // Si el decode lanzara ante Latin-1 inválido, el test falla acá mismo (error #4).
        val decoded = OutputCleaner.decode(rawBytes)
        val cleaned = OutputCleaner.clean(
            decoded,
            command = "display interface GigabitEthernet0/0/1",
            promptRegex = Regex("""^<[\w.\-]+>\s*$"""),
        )
        assertFalse(cleaned.contains("More"), "quedó paginador: $cleaned")
        assertFalse(cleaned.contains("<HUA-CORE-01>"), "quedó el prompt: $cleaned")
        assertTrue(
            cleaned.contains("Speed :  1000,  Duplex: FULL"),
            "la línea de datos debe sobrevivir intacta: $cleaned",
        )
    }
}
