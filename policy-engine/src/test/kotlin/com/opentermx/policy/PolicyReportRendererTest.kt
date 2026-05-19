package com.opentermx.policy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolicyReportRendererTest {

    private val eval = PolicyEvaluation(
        policyName = "baseline", policyVersion = "1.0",
        deviceAlias = "core-router-1",
        target = "running_config",
        results = listOf(
            RuleResult("no-telnet", "high", RuleStatus.FAIL, "Telnet detectado", line = 5),
            RuleResult("ssh-v2", "high", RuleStatus.PASS, "OK"),
            RuleResult("log-buffered", "low", RuleStatus.WARN, "Considera buffered"),
        ),
    )

    @Test
    fun `toJson incluye counts agregados y resultados por rule`() {
        val json = PolicyReportRenderer.toJson(eval)
        assertEquals("baseline", json["policyName"])
        assertEquals(1, json["passCount"])
        assertEquals(1, json["failCount"])
        assertEquals(1, json["warnCount"])
        @Suppress("UNCHECKED_CAST")
        val results = json["results"] as List<Map<String, Any?>>
        assertEquals(3, results.size)
        assertEquals("FAIL", results[0]["status"])
        assertEquals(5, results[0]["line"])
    }

    @Test
    fun `toMarkdown produce tabla legible con header del policy`() {
        val md = PolicyReportRenderer.toMarkdown(eval)
        assertTrue(md.contains("## Policy `baseline` v1.0"))
        assertTrue(md.contains("**Device:** `core-router-1`"))
        assertTrue(md.contains("| Rule | Severity | Status | Message |"))
        assertTrue(md.contains("no-telnet"))
        assertTrue(md.contains("(línea 5)"))
        assertTrue(md.contains("1 PASS / 1 FAIL / 1 WARN"))
    }

    @Test
    fun `audit Markdown agrega header de fleet`() {
        val md = PolicyReportRenderer.toMarkdownAudit("baseline", listOf(eval, eval.copy(deviceAlias = "edge-1")))
        assertTrue(md.startsWith("# Audit — policy `baseline`"))
        assertTrue(md.contains("Devices: 2"))
        assertTrue(md.contains("core-router-1"))
        assertTrue(md.contains("edge-1"))
    }

    @Test
    fun `audit con lista vacía conserva policyName en Markdown y JSON`() {
        val md = PolicyReportRenderer.toMarkdownAudit("baseline", emptyList())
        assertTrue(md.contains("# Audit — policy `baseline`"))
        assertTrue(md.contains("Sin devices"))

        val json = PolicyReportRenderer.toJsonAudit("baseline", emptyList())
        assertEquals("baseline", json["policyName"])
        assertEquals(0, json["deviceCount"])
        assertEquals(0, json["totalFail"])
        assertEquals(0, json["totalWarn"])
    }
}
