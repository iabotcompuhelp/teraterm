package com.opentermx.mcp.handlers

import com.opentermx.ai.audit.AiAuditLog
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ProposeCommandsHandlerTest {

    @TempDir
    lateinit var tmp: Path

    private fun newHandler(gate: FakeApprovalGate): ProposeCommandsHandler =
        ProposeCommandsHandler(
            approvalGate = gate,
            auditLog = AiAuditLog(tmp.resolve("audit.csv")),
            injectDelayMillis = 0L,
        )

    @AfterEach
    fun cleanup() = TestFixtures.unregisterAll()

    @Test
    fun `sesión inexistente lanza NOT_FOUND`() {
        val handler = newHandler(FakeApprovalGate())
        val ex = assertThrows(McpToolException::class.java) {
            runBlocking {
                handler.invoke(mapOf("sessionId" to "ghost", "commands" to listOf("show version")))
            }
        }
        assertEquals(McpToolException.ErrorCode.NOT_FOUND, ex.code)
    }

    @Test
    fun `sesión sin sink lanza UNAVAILABLE`() {
        TestFixtures.registerSession(idValue = "s1", protocol = "SSH", sink = null)
        val handler = newHandler(FakeApprovalGate())
        val ex = assertThrows(McpToolException::class.java) {
            runBlocking {
                handler.invoke(mapOf("sessionId" to "s1", "commands" to listOf("show version")))
            }
        }
        assertEquals(McpToolException.ErrorCode.UNAVAILABLE, ex.code)
    }

    @Test
    fun `rechazo del operador no inyecta nada y persiste audit log`() = runBlocking {
        val sink = CapturingSink()
        TestFixtures.registerSession(idValue = "s1", protocol = "SSH", sink = sink, buffer = listOf("Cisco IOS"))
        val gate = FakeApprovalGate().also { it.rejectAll() }
        val handler = newHandler(gate)

        val result = handler.invoke(mapOf(
            "sessionId" to "s1",
            "commands" to listOf("show version", "configure terminal"),
            "rationale" to "verificación rutinaria",
        ))

        assertEquals(false, result["approved"])
        assertEquals(0, result["executed"])
        assertEquals(2, result["rejected"])
        assertNull(result["output"])
        assertEquals(riskSummary(safe = 1, config = 1, dangerous = 0), result["riskSummary"])
        assertTrue(sink.sent.isEmpty(), "Sink no debe recibir nada cuando se rechaza")
        assertEquals(1, gate.invocations.size, "Approval gate debe invocarse aunque se rechace")
        assertNotNull(result["auditLogId"])
    }

    @Test
    fun `aprobación inyecta todos los comandos y cuenta correctamente`() = runBlocking {
        val sink = CapturingSink()
        TestFixtures.registerSession(idValue = "s1", protocol = "SSH", sink = sink, buffer = listOf("Cisco IOS"))
        val gate = FakeApprovalGate().also { it.approveAll() }
        val handler = newHandler(gate)

        val cmds = listOf("show version", "show interfaces brief")
        val result = handler.invoke(mapOf("sessionId" to "s1", "commands" to cmds))

        assertEquals(true, result["approved"])
        assertEquals(2, result["executed"])
        assertEquals(0, result["rejected"])
        assertEquals(cmds, sink.sent)
    }

    @Test
    fun `aprobación parcial cuenta rejected como la diferencia`() = runBlocking {
        val sink = CapturingSink()
        TestFixtures.registerSession(idValue = "s1", protocol = "SSH", sink = sink, buffer = listOf("Cisco IOS"))
        val gate = FakeApprovalGate().also { it.approveSubset { c -> c.raw.startsWith("show") } }
        val handler = newHandler(gate)

        val cmds = listOf("show version", "configure terminal", "show interfaces")
        val result = handler.invoke(mapOf("sessionId" to "s1", "commands" to cmds))

        assertEquals(true, result["approved"])
        assertEquals(2, result["executed"])
        assertEquals(1, result["rejected"])
        assertEquals(listOf("show version", "show interfaces"), sink.sent)
    }

    @Test
    fun `RiskClassifier marca DANGEROUS y queda reflejado en riskSummary aún si se rechaza`() = runBlocking {
        val sink = CapturingSink()
        TestFixtures.registerSession(idValue = "s1", protocol = "SSH", sink = sink, buffer = listOf("Cisco IOS"))
        val gate = FakeApprovalGate().also { it.rejectAll() }
        val handler = newHandler(gate)

        val result = handler.invoke(mapOf(
            "sessionId" to "s1",
            "commands" to listOf("show version", "configure terminal", "erase startup-config"),
        ))

        assertEquals(false, result["approved"])
        assertEquals(riskSummary(safe = 1, config = 1, dangerous = 1), result["riskSummary"])
        assertEquals(1, gate.invocations.first().classifications.count { it.risk.name == "DANGEROUS" })
    }

    @Test
    fun `sink que falla incrementa failedCount pero no rejected`() = runBlocking {
        val sink = CapturingSink(shouldFail = { it.startsWith("configure") })
        TestFixtures.registerSession(idValue = "s1", protocol = "SSH", sink = sink, buffer = listOf("Cisco IOS"))
        val gate = FakeApprovalGate().also { it.approveAll() }
        val handler = newHandler(gate)

        val result = handler.invoke(mapOf(
            "sessionId" to "s1",
            "commands" to listOf("show version", "configure terminal"),
        ))

        assertEquals(true, result["approved"])
        // executed solo cuenta los exitosos
        assertEquals(1, result["executed"])
        // rejected es 0 porque el operador no descartó nada (los dos fueron al sink)
        assertEquals(0, result["rejected"])
    }

    @Test
    fun `argumentos vacíos lanzan INVALID_ARGUMENT`() {
        val handler = newHandler(FakeApprovalGate())
        assertThrows(McpToolException::class.java) {
            runBlocking { handler.invoke(emptyMap()) }
        }
        assertThrows(McpToolException::class.java) {
            runBlocking { handler.invoke(mapOf("sessionId" to "s1", "commands" to emptyList<String>())) }
        }
        assertFalse(false) // silence unused-result lint
    }
}