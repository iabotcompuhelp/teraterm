package com.opentermx.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RuleEvaluatorTest {

    private fun policy(rules: List<Rule>) = Policy(
        policy = PolicyMeta(name = "test", version = "1.0"),
        rules = rules,
    )

    @Test
    fun `pattern_deny PASS cuando ninguna línea matchea`() {
        val p = policy(listOf(
            Rule(id = "r1", severity = "high", type = "pattern_deny", pattern = "telnet", message = "no telnet"),
        ))
        val res = RuleEvaluator.evaluate(p, "ip ssh version 2\nhostname rt-1", deviceAlias = "d")
        assertEquals(RuleStatus.PASS, res.results.first().status)
        assertEquals(1, res.passCount)
    }

    @Test
    fun `pattern_deny FAIL en la primera línea matcheada con número de línea correcto`() {
        val p = policy(listOf(
            Rule(id = "r1", severity = "high", type = "pattern_deny", pattern = "telnet", message = "no telnet"),
        ))
        val content = "ip ssh version 2\ntransport input telnet\nhostname rt-1"
        val res = RuleEvaluator.evaluate(p, content, deviceAlias = "d")
        val r = res.results.first()
        assertEquals(RuleStatus.FAIL, r.status)
        assertEquals(2, r.line)
        assertEquals("no telnet", r.message)
    }

    @Test
    fun `require PASS cuando alguna línea matchea`() {
        val p = policy(listOf(
            Rule(id = "r1", severity = "high", type = "require", pattern = "ip ssh version 2", message = "falta ssh v2"),
        ))
        val res = RuleEvaluator.evaluate(p, "hostname x\nip ssh version 2", deviceAlias = "d")
        assertEquals(RuleStatus.PASS, res.results.first().status)
        assertEquals(2, res.results.first().line)
    }

    @Test
    fun `require FAIL cuando ninguna matchea`() {
        val p = policy(listOf(
            Rule(id = "r1", severity = "high", type = "require", pattern = "ip ssh version 2", message = "falta ssh v2"),
        ))
        val res = RuleEvaluator.evaluate(p, "hostname x", deviceAlias = "d")
        assertEquals(RuleStatus.FAIL, res.results.first().status)
        assertNull(res.results.first().line)
    }

    @Test
    fun `recommend reporta WARN en lugar de FAIL cuando no matchea`() {
        val p = policy(listOf(
            Rule(id = "r1", severity = "low", type = "recommend", pattern = "logging buffered", message = "considera buffered"),
        ))
        val res = RuleEvaluator.evaluate(p, "hostname x", deviceAlias = "d")
        assertEquals(RuleStatus.WARN, res.results.first().status)
    }

    @Test
    fun `regex inválida produce WARN no error`() {
        val p = policy(listOf(
            Rule(id = "r1", severity = "high", type = "require", pattern = "[unclosed", message = "x"),
        ))
        val res = RuleEvaluator.evaluate(p, "anything", deviceAlias = "d")
        assertEquals(RuleStatus.WARN, res.results.first().status)
        assertNotNull(res.results.first().message)
    }

    @Test
    fun `tipo desconocido produce WARN`() {
        val p = policy(listOf(
            Rule(id = "r1", severity = "high", type = "i_made_this_up", pattern = "x", message = "y"),
        ))
        val res = RuleEvaluator.evaluate(p, "x", deviceAlias = "d")
        assertEquals(RuleStatus.WARN, res.results.first().status)
    }

    @Test
    fun `parser custom afecta las líneas evaluadas`() {
        val skipComments = object : DeviceConfigParser {
            override fun lines(rawContent: String): List<String> =
                rawContent.lines().filterNot { it.trimStart().startsWith("!") }
        }
        val p = policy(listOf(
            Rule(id = "r1", severity = "high", type = "pattern_deny", pattern = "telnet", message = "no telnet"),
        ))
        // El comentario `! transport input telnet` debería ser ignorado.
        val res = RuleEvaluator.evaluate(p,
            "! transport input telnet\nip ssh version 2",
            deviceAlias = "d", parser = skipComments)
        assertEquals(RuleStatus.PASS, res.results.first().status)
    }

    @Test
    fun `counts agregados son consistentes`() {
        val p = policy(listOf(
            Rule(id = "pass1", severity = "high", type = "pattern_deny", pattern = "telnet", message = "x"),
            Rule(id = "fail1", severity = "high", type = "require", pattern = "ip ssh version 2", message = "y"),
            Rule(id = "warn1", severity = "low", type = "recommend", pattern = "logging buffered", message = "z"),
        ))
        val res = RuleEvaluator.evaluate(p, "hostname rt-1", deviceAlias = "d")
        assertEquals(1, res.passCount)
        assertEquals(1, res.failCount)
        assertEquals(1, res.warnCount)
    }
}
