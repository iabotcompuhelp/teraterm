package com.opentermx.app.settings

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regresión de Phase 2.5 T2: los defaults de KEX (y ciphers/MACs) estaban acotados
 * a dos algoritmos modernos. Eso hacía fallar la negociación contra equipos FIPS,
 * Cisco IOS reciente y hardware federal que sólo aceptan ECDH NIST. Tras el fix,
 * los defaults deben incluir el conjunto amplio sin tocar la persistencia de un
 * usuario que ya editó sus listas.
 */
class SshGeneralDefaultsTest {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `kex default incluye ecdh-sha2-nistp256 para equipos FIPS`() {
        val defaults = SshGeneralSettings()
        assertTrue("ecdh-sha2-nistp256" in defaults.kex, "kex=${defaults.kex}")
    }

    @Test
    fun `kex default cubre los 8 algoritmos esperados`() {
        val expected = listOf(
            "curve25519-sha256",
            "curve25519-sha256@libssh.org",
            "ecdh-sha2-nistp256",
            "ecdh-sha2-nistp384",
            "ecdh-sha2-nistp521",
            "diffie-hellman-group-exchange-sha256",
            "diffie-hellman-group16-sha512",
            "diffie-hellman-group14-sha256",
        )
        assertEquals(expected, SshGeneralSettings().kex)
    }

    @Test
    fun `ciphers default incluye aes192-ctr y variantes gcm sin sufijo openssh`() {
        val ciphers = SshGeneralSettings().ciphers
        assertTrue("aes192-ctr" in ciphers, "ciphers=$ciphers")
        assertTrue("aes256-gcm" in ciphers)
        assertTrue("aes128-gcm" in ciphers)
    }

    @Test
    fun `macs default incluye hmac-sha2-256, hmac-sha2-512 y hmac-sha1 legacy`() {
        val macs = SshGeneralSettings().macs
        assertTrue("hmac-sha2-256" in macs, "macs=$macs")
        assertTrue("hmac-sha2-512" in macs)
        assertTrue("hmac-sha1" in macs)
    }

    @Test
    fun `settings persistidos con lista vieja sobreviven al roundtrip JSON`() {
        // Caso real: un usuario guardó settings antes de que ampliáramos los defaults.
        // Jackson debe respetar SU lista literal, no aplicar el default expandido.
        val legacyJson = """
            {
              "sshGeneral": {
                "kex": ["curve25519-sha256", "diffie-hellman-group-exchange-sha256"],
                "ciphers": ["aes256-ctr"],
                "macs": ["hmac-sha2-256-etm@openssh.com"]
              }
            }
        """.trimIndent()

        val restored = mapper.readValue(legacyJson, AppSettings::class.java)

        assertEquals(listOf("curve25519-sha256", "diffie-hellman-group-exchange-sha256"), restored.sshGeneral.kex)
        assertEquals(listOf("aes256-ctr"), restored.sshGeneral.ciphers)
        assertEquals(listOf("hmac-sha2-256-etm@openssh.com"), restored.sshGeneral.macs)
        // Confirmación del invariante: la elección del usuario gana sobre el default amplio.
        assertFalse("ecdh-sha2-nistp256" in restored.sshGeneral.kex)
    }

    @Test
    fun `JSON sin sshGeneral usa el default amplio nuevo`() {
        // Settings recién creados (sin sshGeneral persistido) deben recibir los defaults
        // expandidos. Cubre el caso del operador que instala fresh y conecta a un Cisco.
        val emptyJson = """{}"""

        val restored = mapper.readValue(emptyJson, AppSettings::class.java)

        assertTrue("ecdh-sha2-nistp256" in restored.sshGeneral.kex)
        assertTrue("aes192-ctr" in restored.sshGeneral.ciphers)
        assertTrue("hmac-sha1" in restored.sshGeneral.macs)
    }
}
