package com.opentermx.fingerprint

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.opentermx.fingerprint.probes.MikrotikSystemResourceProbe
import com.opentermx.netparsers.Vendor
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode

/**
 * Fixture-driven como [com.opentermx.netparsers.FixtureParserTest]: cada
 * `fixtures/fingerprint/<vendor>/<caso>.txt` se extrae con la sonda de su carpeta y se
 * compara contra `<caso>.expected.json` en modo STRICT.
 */
class FingerprintFixtureTest {

    private val mapper = ObjectMapper().registerKotlinModule()

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixturePairs")
    fun `la sonda extrae la identidad canonica esperada`(label: String, txt: Path, expected: Path) {
        val probe = probeForFixture(txt)
        val output = Files.readString(txt)
        assertTrue(probe.matches(output), "la sonda ${probe.id} debería matchear $label")
        assertFalse(CliRejection.isRejection(output), "$label no es un rechazo de CLI")
        val identity = probe.extract(output)
        assertNotNull(identity, "extract() no debería dar null para $label")
        JSONAssert.assertEquals(
            Files.readString(expected),
            mapper.writeValueAsString(identity!!.toJson()),
            JSONCompareMode.STRICT,
        )
    }

    @Test
    fun `el fixture de no-match es rechazo de CLI y ninguna sonda lo matchea`() {
        val output = Files.readString(fixturesRoot().resolve("_nomatch/percent_invalid_input.txt"))
        assertTrue(CliRejection.isRejection(output), "`% Invalid input` es un no-match esperado (error #33)")
        ProbeRegistry.all().forEach { probe ->
            assertFalse(probe.matches(output), "${probe.id} no debería matchear un rechazo de CLI")
            assertNull(probe.extract(output), "${probe.id} no debería extraer nada de un rechazo")
        }
    }

    @Test
    fun `stack con modelos mixtos guarda todos los seriales y stackMembers (error 35)`() {
        val txt = fixturesRoot().resolve("cisco_iosxe/show_version_iosxe17.3_c9300_stack.txt")
        val identity = probeForFixture(txt).extract(Files.readString(txt))!!
        assertEquals(3, identity.serialNumbers.size, "un serial por miembro del stack")
        assertEquals("C9300-48P", identity.model, "el modelo reportado es el del master")
        assertEquals(
            "C9300-48P,C9300-48P,C9300-24UX",
            identity.extras["stackMembers"],
            "modelos mixtos van a extras.stackMembers",
        )
    }

    @Test
    fun `enrich de mikrotik agrega el serial del routerboard sin degradar la identidad`() {
        val probe = MikrotikSystemResourceProbe()
        val resource = Files.readString(fixturesRoot().resolve("mikrotik/system_resource_print_ros7_ccr.txt"))
        val routerboard = Files.readString(fixturesRoot().resolve("mikrotik/system_routerboard_print_ros7.txt"))
        val base = probe.extract(resource)!!

        val enriched = probe.enrich(base, routerboard)
        assertEquals(listOf("9AB3045D7E2F"), enriched.serialNumbers)
        assertEquals(base.model, enriched.model)
        assertEquals(base.osVersion, enriched.osVersion)

        // Output secundario inútil => la identidad queda intacta, jamás se degrada.
        assertEquals(base, probe.enrich(base, "lorem ipsum"))
    }

    @Test
    fun `ninguna sonda lanza ante basura — matches false y extract null`() {
        val garbage = listOf(
            "",
            " ",
            "lorem ipsum dolor sit amet 12345",
            "%Error opening flash:/ (No such file or directory)",
            "^\n% Invalid input detected at '^' marker.",
            "{\"esto\": \"es json, no output de equipo\"}",
        )
        ProbeRegistry.all().forEach { probe ->
            garbage.forEach { g ->
                assertFalse(probe.matches(g), "${probe.id} no debería matchear basura")
                assertNull(probe.extract(g), "${probe.id} no debería extraer identidad de basura")
            }
        }
    }

    @Test
    fun `el registry resuelve sonda por vendor y deja sin sonda a los no soportados`() {
        assertEquals("cisco_show_version", ProbeRegistry.forVendor(Vendor.CISCO_IOSXE)?.id)
        assertEquals("cisco_show_version", ProbeRegistry.forVendor(Vendor.CISCO_NXOS)?.id)
        assertEquals("mikrotik_system_resource", ProbeRegistry.forVendor(Vendor.MIKROTIK)?.id)
        assertNull(ProbeRegistry.forVendor(Vendor.UNKNOWN))
        assertNull(ProbeRegistry.forVendor(Vendor.JUNIPER_JUNOS))
        assertEquals(
            ProbeRegistry.all().map { it.order },
            ProbeRegistry.all().map { it.order }.sorted(),
            "la cadena sale ordenada por order",
        )
    }

    companion object {

        fun fixturesRoot(): Path {
            val url = FingerprintFixtureTest::class.java.getResource("/fixtures/fingerprint")
                ?: error("directorio de fixtures de fingerprint ausente del classpath de test")
            return Path.of(url.toURI())
        }

        @JvmStatic
        fun fixturePairs(): Stream<Arguments> = Files.walk(fixturesRoot())
            .filter { it.fileName.toString().endsWith(".txt") }
            .filter { !it.toString().contains("_nomatch") }
            .filter { !it.fileName.toString().startsWith("system_routerboard_print") }
            .map { txt ->
                val expected = txt.resolveSibling(
                    txt.fileName.toString().removeSuffix(".txt") + ".expected.json",
                )
                check(Files.isRegularFile(expected)) { "falta $expected para $txt" }
                Arguments.of("${txt.parent.fileName}/${txt.fileName}", txt, expected)
            }

        /** Resuelve la sonda por carpeta del vendor del fixture. */
        fun probeForFixture(txt: Path): FingerprintProbe = when (txt.parent.fileName.toString()) {
            "cisco_ios" -> ProbeRegistry.forVendor(Vendor.CISCO_IOS)!!
            "cisco_iosxe" -> ProbeRegistry.forVendor(Vendor.CISCO_IOSXE)!!
            "cisco_nxos" -> ProbeRegistry.forVendor(Vendor.CISCO_NXOS)!!
            "huawei_vrp" -> ProbeRegistry.forVendor(Vendor.HUAWEI_VRP)!!
            "aruba_aoscx" -> ProbeRegistry.forVendor(Vendor.ARUBA_AOSCX)!!
            "fortinet" -> ProbeRegistry.forVendor(Vendor.FORTINET)!!
            "mikrotik" -> ProbeRegistry.forVendor(Vendor.MIKROTIK)!!
            else -> error("fixture sin sonda mapeada: $txt")
        }
    }
}
