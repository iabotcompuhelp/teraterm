package com.opentermx.telemetrydb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigSanitizerTest {

    @Test
    fun `enable secret se reemplaza por REDACTED conservando la keyword`() {
        val raw = """
            hostname SW-CORE-01
            enable secret 5 ${'$'}1${'$'}abcd${'$'}XyZ123456789
            interface GigabitEthernet0/1
             description UPLINK
        """.trimIndent()
        val clean = ConfigSanitizer.sanitize(raw)
        assertFalse(clean.contains("abcd"), "el hash del secret no debe sobrevivir: $clean")
        assertTrue(clean.contains("enable secret <REDACTED>"), clean)
        assertTrue(clean.contains("hostname SW-CORE-01"), "las líneas sin secretos quedan intactas")
        assertTrue(clean.contains("description UPLINK"))
    }

    @Test
    fun `password community y pre-shared-key tambien se redactan`() {
        val raw = listOf(
            "username admin password 7 0822455D0A16",
            "snmp-server community S3cr3tRO ro",
            "crypto isakmp key MiClaveVPN address 10.0.0.1",
            "set vpn ipsec pre-shared-key TopSecret123",
            "standby 1 ip 10.0.0.254",
        ).joinToString("\n")
        val clean = ConfigSanitizer.sanitize(raw)
        assertFalse(clean.contains("0822455D0A16"))
        assertFalse(clean.contains("S3cr3tRO"))
        assertFalse(clean.contains("MiClaveVPN"))
        assertFalse(clean.contains("TopSecret123"))
        assertTrue(clean.contains("standby 1 ip 10.0.0.254"), "línea sin secreto intacta")
    }

    @Test
    fun `el hash se calcula sobre el texto sanitizado — secreto rotado deduplica igual`() {
        val configA = "hostname R1\nenable secret 5 \$1\$aaa\$111"
        val configB = "hostname R1\nenable secret 5 \$1\$bbb\$222"
        val hashA = ConfigSanitizer.sha256(ConfigSanitizer.sanitize(configA))
        val hashB = ConfigSanitizer.sha256(ConfigSanitizer.sanitize(configB))
        assertEquals(hashA, hashB, "mismo config con secreto rotado debe dar el mismo hash")
        assertEquals(64, hashA.length)
    }
}
