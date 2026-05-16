package com.opentermx.ai.safety

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CredentialRedactorTest {

    private val redactor = CredentialRedactor()

    @Test
    fun `enable secret se redacta`() {
        val out = redactor.redact("enable secret 5 \$1\$abcd\$efgh")
        assertEquals("enable secret 5 ********", out)
    }

    @Test
    fun `password en linea de config se redacta`() {
        val out = redactor.redact("username admin password 7 0822455D0A16")
        assertTrue(out.endsWith("********"))
        assertTrue(out.startsWith("username admin password"))
    }

    @Test
    fun `show version no toca password porque no aparece como keyword`() {
        val original = "router> show version"
        assertEquals(original, redactor.redact(original))
    }

    @Test
    fun `snmp community se redacta`() {
        val out = redactor.redact("snmp-server community public RW")
        assertTrue(out.startsWith("snmp-server community ********"))
    }

    @Test
    fun `tacacs key se redacta`() {
        val out = redactor.redact("tacacs-server key 0 ClaveSuperSecreta")
        assertTrue(out.startsWith("tacacs-server key ********"))
    }

    @Test
    fun `radius key se redacta`() {
        val out = redactor.redact("radius-server key 7 abcd1234")
        assertTrue(out.startsWith("radius-server key ********"))
    }

    @Test
    fun `bearer token http se redacta`() {
        val out = redactor.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.signature")
        assertEquals("Authorization: Bearer ********", out)
    }

    @Test
    fun `bloque PEM completo se redacta`() {
        val pem = """
            -----BEGIN RSA PRIVATE KEY-----
            MIIEowIBAAKCAQEA0...
            xxxx
            -----END RSA PRIVATE KEY-----
        """.trimIndent()
        val out = redactor.redact(pem)
        assertEquals("[redacted-private-key]", out.trim())
    }

    @Test
    fun `texto sin matches queda igual`() {
        val original = "interface GigabitEthernet0/1\n ip address 10.0.0.1 255.255.255.0\n"
        assertEquals(original, redactor.redact(original))
    }

    @Test
    fun `redactor con reglas custom aplica las propias y omite las built-in`() {
        val custom = CredentialRedactor(
            rules = listOf(
                RedactionRule(Regex("HOLA"), "ADIOS", description = "test"),
            ),
        )
        // built-in no aplica
        val passOut = custom.redact("enable secret 5 xxxx")
        assertEquals("enable secret 5 xxxx", passOut)
        // custom aplica
        assertEquals("ADIOS mundo", custom.redact("HOLA mundo"))
    }

    @Test
    fun `redact lines preserva tamaño de la lista`() {
        val lines = listOf(
            "interface Vlan10",
            "snmp-server community private RO",
            "exit",
        )
        val out = redactor.redactLines(lines)
        assertEquals(3, out.size)
        assertEquals("interface Vlan10", out[0])
        assertTrue(out[1].contains("********"))
        assertEquals("exit", out[2])
    }

    @Test
    fun `redactor con string vacio no falla`() {
        assertEquals("", redactor.redact(""))
        assertEquals(emptyList<String>(), redactor.redactLines(emptyList()))
        assertFalse(false)
    }
}