package com.opentermx.mcp.security

import com.opentermx.ai.context.Vendor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whitelist v2 (Fase 6A): perfiles con nombre referenciados por el catálogo de modelos.
 * Un perfil resuelto reemplaza el `allow` del vendor y suma su `deny` al global; un
 * perfil inexistente cae al vendor (jamás abre la puerta). v1 sigue siendo válido.
 */
class ReadOnlyProfilesTest {

    private val validator = ReadOnlyCommandValidator.embedded()

    @Test
    fun `el embebido v2 carga los perfiles del catalog pack inicial`() {
        assertTrue(validator.profileNames().containsAll(setOf("hpe_comware", "arubaos_switch")))
    }

    @Test
    fun `el vendor nuevo HPE_COMWARE acepta display y rechaza system-view`() {
        assertEquals(ReadOnlyValidation.Allowed, validator.validate("display interface brief", Vendor.HPE_COMWARE))
        assertTrue(validator.validate("system-view", Vendor.HPE_COMWARE) is ReadOnlyValidation.Rejected)
        assertTrue(
            validator.validate("display diagnostic-information", Vendor.HPE_COMWARE) is ReadOnlyValidation.Rejected,
            "deny global (error #12)",
        )
    }

    @Test
    fun `un perfil resuelto reemplaza el allow del vendor y suma su deny`() {
        // ARUBA_OS (whitelist amplia de vendor) acotado por el perfil del 2930F:
        assertEquals(
            ReadOnlyValidation.Allowed,
            validator.validate("show interfaces brief", Vendor.ARUBA_OS, profile = "arubaos_switch"),
        )
        assertTrue(
            validator.validate("show tech all", Vendor.ARUBA_OS, profile = "arubaos_switch")
                is ReadOnlyValidation.Rejected,
            "deny del perfil gana aunque el allow del vendor lo dejara pasar",
        )
        // El perfil hpe_comware NO permite `show ...` (Comware es display).
        assertTrue(
            validator.validate("show version", Vendor.ARUBA_OS, profile = "hpe_comware")
                is ReadOnlyValidation.Rejected,
        )
    }

    @Test
    fun `perfil inexistente cae a la whitelist del vendor, jamas abre la puerta`() {
        assertEquals(
            ReadOnlyValidation.Allowed,
            validator.validate("show version", Vendor.CISCO_IOS, profile = "no_existe"),
        )
        assertTrue(
            validator.validate("reload", Vendor.CISCO_IOS, profile = "no_existe")
                is ReadOnlyValidation.Rejected,
        )
    }

    @Test
    fun `patternsFor con perfil devuelve los patrones del perfil`() {
        val profilePatterns = validator.patternsFor(Vendor.ARUBA_OS, profile = "hpe_comware")
        assertTrue(profilePatterns.any { "display" in it })
        assertTrue(profilePatterns.none { "show" in it })
        assertTrue(validator.patternsFor(Vendor.ARUBA_OS).any { "show" in it }, "sin perfil: vendor")
    }

    @Test
    fun `version 1 sin profiles sigue siendo valida y version futura se rechaza`() {
        val v1 = ReadOnlyCommandValidator.fromYaml(
            """
            version: 1
            vendors:
              CISCO_IOS:
                - '^show\s+\S+.*${'$'}'
            """.trimIndent()
        )
        assertEquals(ReadOnlyValidation.Allowed, v1.validate("show version", Vendor.CISCO_IOS))
        assertTrue(v1.profileNames().isEmpty())

        assertThrows(IllegalArgumentException::class.java) {
            ReadOnlyCommandValidator.fromYaml("version: 9\nvendors:\n  CISCO_IOS:\n    - '^show .*$'\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReadOnlyCommandValidator.fromYaml(
                "version: 2\nvendors:\n  CISCO_IOS:\n    - '^show .*$'\nprofiles:\n  vacio:\n    deny: ['x']\n"
            )
        }
    }
}
