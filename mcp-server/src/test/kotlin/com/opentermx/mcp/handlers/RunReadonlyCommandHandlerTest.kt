package com.opentermx.mcp.handlers

import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.mcp.security.ApprovalDecision
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RunReadonlyCommandHandlerTest {

    @TempDir
    lateinit var tmp: Path

    private fun newHandler(
        gate: FakeApprovalGate = FakeApprovalGate(),
        autoApprove: Boolean = false,
    ): RunReadonlyCommandHandler = RunReadonlyCommandHandler(
        approvalGate = gate,
        autoApprove = { autoApprove },
        auditLog = AiAuditLog(tmp.resolve("audit.csv")),
        outputWaitMillis = 0L,
    )

    @AfterEach
    fun cleanup() = TestFixtures.unregisterAll()

    @Test
    fun `sesión inexistente lanza NOT_FOUND`() {
        val ex = assertThrows(McpToolException::class.java) {
            runBlocking { newHandler().invoke(mapOf("sessionId" to "ghost", "command" to "show version")) }
        }
        assertEquals(McpToolException.ErrorCode.NOT_FOUND, ex.code)
    }

    @Test
    fun `comando mutativo se rechaza con INVALID_ARGUMENT sin invocar gate ni sink`() {
        val sink = CapturingSink()
        TestFixtures.registerSession(idValue = "s1", protocol = "SSH", sink = sink, buffer = listOf("Cisco IOS"))
        val gate = FakeApprovalGate().also { it.approveAll() }
        val handler = newHandler(gate, autoApprove = true)

        for (cmd in listOf("configure terminal", "reload", "show run | redirect tftp://x", "show version\nreload")) {
            val ex = assertThrows(McpToolException::class.java) {
                runBlocking { handler.invoke(mapOf("sessionId" to "s1", "command" to cmd)) }
            }
            assertEquals(McpToolException.ErrorCode.INVALID_ARGUMENT, ex.code, "para `$cmd`")
        }
        assertTrue(sink.sent.isEmpty(), "nada debe llegar al sink")
        assertTrue(gate.invocations.isEmpty(), "la whitelist corre antes del gate")
    }

    @Test
    fun `auto-approve ejecuta sin gate y devuelve output`() = runBlocking {
        val sink = CapturingSink()
        TestFixtures.registerSession(
            idValue = "s1", protocol = "SSH", sink = sink,
            buffer = listOf("Cisco IOS Software", "router-1#"),
        )
        val gate = FakeApprovalGate() // default: rechaza — si se invocara, el test fallaría
        val handler = newHandler(gate, autoApprove = true)

        val result = handler.invoke(mapOf("sessionId" to "s1", "command" to "show version"))

        assertEquals(true, result["approved"])
        assertEquals(true, result["autoApproved"])
        assertEquals(1, result["executed"])
        assertEquals(listOf("show version"), sink.sent)
        assertTrue(gate.invocations.isEmpty(), "auto-approve no debe abrir el gate")
        assertNotNull(result["output"])
        assertNotNull(result["auditLogId"])
    }

    @Test
    fun `sin auto-approve el gate se invoca y su rechazo no inyecta nada`() = runBlocking {
        val sink = CapturingSink()
        TestFixtures.registerSession(idValue = "s1", protocol = "SSH", sink = sink, buffer = listOf("Cisco IOS"))
        val gate = FakeApprovalGate().also { it.rejectAll() }
        val handler = newHandler(gate, autoApprove = false)

        val result = handler.invoke(mapOf("sessionId" to "s1", "command" to "show version"))

        assertEquals(false, result["approved"])
        assertEquals(false, result["autoApproved"])
        assertEquals(0, result["executed"])
        assertNull(result["output"])
        assertTrue(sink.sent.isEmpty())
        assertEquals(1, gate.invocations.size)
    }

    @Test
    fun `sin auto-approve la aprobación ejecuta y marca autoApproved=false`() = runBlocking {
        val sink = CapturingSink()
        TestFixtures.registerSession(idValue = "s1", protocol = "SSH", sink = sink, buffer = listOf("Cisco IOS"))
        val gate = FakeApprovalGate().also { it.approveAll() }
        val handler = newHandler(gate, autoApprove = false)

        val result = handler.invoke(mapOf("sessionId" to "s1", "command" to "show ip route"))

        assertEquals(true, result["approved"])
        assertEquals(false, result["autoApproved"])
        assertEquals(1, result["executed"])
        assertEquals(listOf("show ip route"), sink.sent)
    }

    @Test
    fun `edición del operador a un comando no read-only aborta todo (fail-closed)`() {
        val sink = CapturingSink()
        TestFixtures.registerSession(idValue = "s1", protocol = "SSH", sink = sink, buffer = listOf("Cisco IOS"))
        val gate = FakeApprovalGate().also {
            it.nextDecision = { _ ->
                ApprovalDecision.Approve(
                    listOf("erase startup-config"),
                    listOf(com.opentermx.ai.safety.RiskLevel.DANGEROUS),
                )
            }
        }
        val handler = newHandler(gate, autoApprove = false)

        val ex = assertThrows(McpToolException::class.java) {
            runBlocking { handler.invoke(mapOf("sessionId" to "s1", "command" to "show version")) }
        }
        assertEquals(McpToolException.ErrorCode.INVALID_ARGUMENT, ex.code)
        assertTrue(sink.sent.isEmpty(), "la edición mutativa no debe ejecutarse")
    }

    @Test
    fun `falta de command lanza INVALID_ARGUMENT`() {
        TestFixtures.registerSession(idValue = "s1", protocol = "SSH", sink = CapturingSink())
        val ex = assertThrows(McpToolException::class.java) {
            runBlocking { newHandler().invoke(mapOf("sessionId" to "s1")) }
        }
        assertEquals(McpToolException.ErrorCode.INVALID_ARGUMENT, ex.code)
    }
}
