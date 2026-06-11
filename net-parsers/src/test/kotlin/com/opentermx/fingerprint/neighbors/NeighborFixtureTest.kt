package com.opentermx.fingerprint.neighbors

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.opentermx.netparsers.OutputParser
import com.opentermx.netparsers.ParseResult
import com.opentermx.netparsers.Vendor
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode

class NeighborFixtureTest {

    private val mapper = ObjectMapper().registerKotlinModule()

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixturePairs")
    fun `el parser de vecinos produce el JSON canonico esperado`(label: String, txt: Path, expected: Path) {
        val parser = parserForFixture(txt)
        val result = parser.parse(Files.readString(txt))
        assertTrue(result !is ParseResult.Failure, "el fixture $label no debería dar Failure: $result")
        val entries = when (result) {
            is ParseResult.Success -> result.data
            is ParseResult.PartialSuccess -> result.data
            is ParseResult.Failure -> error("inalcanzable")
        }
        JSONAssert.assertEquals(
            Files.readString(expected),
            mapper.writeValueAsString(entries.map { it.toJson() }),
            JSONCompareMode.STRICT,
        )
    }

    @Test
    fun `ningun parser de vecinos lanza ante basura — devuelve Failure`() {
        val garbage = listOf(
            "",
            " ",
            "lorem ipsum dolor sit amet 12345",
            "^\n% Invalid input detected at '^' marker.",
            "{\"esto\": \"es json, no output de equipo\"}",
        )
        NeighborParserRegistry.all().forEach { parser ->
            garbage.forEach { g ->
                val result = parser.parse(g)
                assertTrue(
                    result is ParseResult.Failure,
                    "${parser.javaClass.simpleName} debería dar Failure ante basura, dio $result",
                )
            }
        }
    }

    @Test
    fun `cero vecinos es Success vacio, no Failure`() {
        val cdp = NeighborParserRegistry.forCommand(Vendor.CISCO_IOS, "show cdp neighbors detail")!!
        val result = cdp.parse("Total cdp entries displayed : 0")
        assertEquals(ParseResult.Success(emptyList<NeighborEntry>()), result)
    }

    @Test
    fun `el registry resuelve comando por vendor y null para los sin soporte`() {
        assertEquals("show cdp neighbors detail", NeighborParserRegistry.neighborCommand(Vendor.CISCO_IOSXE))
        assertEquals("display lldp neighbor", NeighborParserRegistry.neighborCommand(Vendor.HUAWEI_VRP))
        assertEquals("/ip neighbor print detail", NeighborParserRegistry.neighborCommand(Vendor.MIKROTIK))
        assertNull(NeighborParserRegistry.neighborCommand(Vendor.FORTINET))
        assertNull(NeighborParserRegistry.neighborCommand(Vendor.ARUBA_AOSCX))
        // El comando que el runner manda a MikroTik lleva `without-paging` appendeado:
        // el parser tiene que resolverse igual.
        assertEquals(
            "MikrotikIpNeighborParser",
            NeighborParserRegistry.forCommand(
                Vendor.MIKROTIK, "/ip neighbor print detail without-paging",
            )?.javaClass?.simpleName,
        )
    }

    companion object {

        fun fixturesRoot(): Path {
            val url = NeighborFixtureTest::class.java.getResource("/fixtures/neighbors")
                ?: error("directorio de fixtures de vecinos ausente del classpath de test")
            return Path.of(url.toURI())
        }

        @JvmStatic
        fun fixturePairs(): Stream<Arguments> = Files.walk(fixturesRoot())
            .filter { it.fileName.toString().endsWith(".txt") }
            .map { txt ->
                val expected = txt.resolveSibling(
                    txt.fileName.toString().removeSuffix(".txt") + ".expected.json",
                )
                check(Files.isRegularFile(expected)) { "falta $expected para $txt" }
                Arguments.of("${txt.parent.fileName}/${txt.fileName}", txt, expected)
            }

        fun parserForFixture(txt: Path): OutputParser<List<NeighborEntry>> {
            val folder = txt.parent.fileName.toString()
            val file = txt.fileName.toString()
            return when {
                folder == "cisco_ios" && file.startsWith("show_cdp") ->
                    NeighborParserRegistry.forCommand(Vendor.CISCO_IOS, "show cdp neighbors detail")!!
                folder == "cisco_ios" && file.startsWith("show_lldp") ->
                    NeighborParserRegistry.forCommand(Vendor.CISCO_IOS, "show lldp neighbors detail")!!
                folder == "huawei_vrp" ->
                    NeighborParserRegistry.forCommand(Vendor.HUAWEI_VRP, "display lldp neighbor")!!
                folder == "mikrotik" ->
                    NeighborParserRegistry.forCommand(Vendor.MIKROTIK, "/ip neighbor print detail")!!
                else -> error("fixture sin parser mapeado: $txt")
            }
        }
    }
}
