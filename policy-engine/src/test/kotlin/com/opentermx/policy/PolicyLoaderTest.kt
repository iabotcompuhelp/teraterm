package com.opentermx.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolicyLoaderTest {

    @Test
    fun `YAML válido carga con todos los campos`() {
        val yaml = """
            policy:
              name: "baseline-test"
              version: "1.0"
              applies_to:
                device_types: ["cisco_ios"]
                tags_any: ["core"]
            rules:
              - id: "no-telnet"
                severity: "high"
                type: "pattern_deny"
                target: "running_config"
                pattern: "telnet"
                message: "no telnet"
        """.trimIndent()
        val p = PolicyLoader.fromYamlString(yaml)
        assertEquals("baseline-test", p.policy.name)
        assertEquals("1.0", p.policy.version)
        assertEquals(listOf("cisco_ios"), p.policy.appliesTo?.deviceTypes)
        assertEquals(1, p.rules.size)
        assertEquals("no-telnet", p.rules[0].id)
    }

    @Test
    fun `falta version dispara error de schema`() {
        val bad = """
            policy:
              name: "x"
            rules:
              - id: "r1"
                severity: "high"
                type: "pattern_deny"
                pattern: "x"
                message: "y"
        """.trimIndent()
        val ex = assertThrows(PolicyException::class.java) {
            PolicyLoader.fromYamlString(bad)
        }
        assertTrue(ex.message!!.contains("version") || ex.message!!.contains("required"),
            "mensaje real: ${ex.message}")
    }

    @Test
    fun `type fuera del enum es rechazado`() {
        val bad = """
            policy:
              name: "x"
              version: "1.0"
            rules:
              - id: "r1"
                severity: "high"
                type: "i_made_this_up"
                pattern: "x"
                message: "y"
        """.trimIndent()
        val ex = assertThrows(PolicyException::class.java) {
            PolicyLoader.fromYamlString(bad)
        }
        assertTrue(ex.message!!.contains("enum") || ex.message!!.contains("i_made_this_up"),
            "mensaje real: ${ex.message}")
    }

    @Test
    fun `severity fuera del enum es rechazado`() {
        val bad = """
            policy:
              name: "x"
              version: "1.0"
            rules:
              - id: "r1"
                severity: "wat"
                type: "pattern_deny"
                pattern: "x"
                message: "y"
        """.trimIndent()
        val ex = assertThrows(PolicyException::class.java) {
            PolicyLoader.fromYamlString(bad)
        }
        assertTrue(ex.message!!.contains("enum") || ex.message!!.contains("wat"))
    }

    @Test
    fun `rules vacío es rechazado por minItems`() {
        val bad = """
            policy:
              name: "x"
              version: "1.0"
            rules: []
        """.trimIndent()
        val ex = assertThrows(PolicyException::class.java) {
            PolicyLoader.fromYamlString(bad)
        }
        // everit reporta variantes según versión: "minItems", "item count", "expected minimum: 1".
        val msg = ex.message!!.lowercase()
        assertTrue(
            msg.contains("minitems") || msg.contains("empty") || msg.contains("item count") ||
                msg.contains("minimum") || msg.contains("expected") && msg.contains("1"),
            "mensaje real: ${ex.message}",
        )
    }
}
