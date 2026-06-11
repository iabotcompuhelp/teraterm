package com.opentermx.mcp.handlers

import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.mcp.exec.SessionCommandRunner
import com.opentermx.mcp.security.ApprovalDecision
import com.opentermx.mcp.security.ReadOnlyCommandValidator
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RunReadonlyCommandHandlerTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var auditCsv: Path

    private fun newHandler(
        gate: FakeApprovalGate = FakeApprovalGate(),
        allowWithoutApproval: Boolean = true,
    ): RunReadonlyCommandHandler {
        auditCsv = tmp.resolve("audit.csv")
        return RunReadonlyCommandHandler(
            approvalGate = gate,
            allowWithoutApproval = { allowWithoutApproval },
            auditLog = AiAuditLog(auditCsv),
            runner = SessionCommandRunner(pollIntervalMillis = 15, rawSender = { null }),
            validatorProvider = { ReadOnlyCommandValidator.embedded() },
        )
    }

    private fun auditContains(text: String): Boolean =
        Files.exists(auditCsv) && Files.readString(auditCsv).contains(text)

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
    fun `vendor desconocido rechaza sin adivinar y queda auditado`() {
        // Buffer vacío => VendorDetector no tiene con qué trabajar.
        val device = FakeDevice(initialBuffer = emptyList())
        device.register("s1")
        val handler = newHandler()

        val ex = assertThrows(McpToolException::class.java) {
            runBlocking { handler.invoke(mapOf("sessionId" to "s1", "command" to "show version")) }
        }
        assertEquals(McpToolException.ErrorCode.INVALID_ARGUMENT, ex.code)
        assertTrue(device.received.isEmpty(), "nada debe llegar al device")
        assertTrue(auditContains("vendor no detectado"), "el rechazo debe quedar auditado")
    }

    @Test
    fun `comando de configuracion se rechaza con error y queda auditado`() {
        val device = FakeDevice()
        device.register("s1")
        val gate = FakeApprovalGate().also { it.approveAll() }
        val handler = newHandler(gate)

        val ex = assertThrows(McpToolException::class.java) {
            runBlocking { handler.invoke(mapOf("sessionId" to "s1", "command" to "configure terminal")) }
        }
        assertEquals(McpToolException.ErrorCode.INVALID_ARGUMENT, ex.code)
        assertTrue(device.received.isEmpty(), "nada debe llegar al device")
        assertTrue(gate.invocations.isEmpty(), "la whitelist corre antes del gate")
        assertTrue(auditContains("configure terminal"), "el intento debe quedar auditado")
    }

    @Test
    fun `comando permitido ejecuta sin gate y devuelve el output limpio`() = runBlocking {
        val device = FakeDevice()
        device.register("s1")
        val gate = FakeApprovalGate() // default: rechazaría — no debe ser invocado
        val handler = newHandler(gate, allowWithoutApproval = true)

        val result = handler.invoke(mapOf("sessionId" to "s1", "command" to "show version"))

        assertEquals(true, result["approved"])
        assertEquals(false, result["timedOut"])
        assertEquals("show version", result["command"])
        assertEquals("Cisco IOS", result["vendor"])
        val output = result["output"] as String
        assertTrue(output.contains("output de [show version] linea 1"), "output real esperado, vino `$output`")
        assertTrue(gate.invocations.isEmpty(), "con el setting ON no hay gate")
        assertTrue((result["durationMs"] as Long) >= 0)
        assertNotNull(result["auditLogId"])
        assertTrue(auditContains("show version"), "la ejecución queda auditada")
    }

    @Test
    fun `timeout devuelve parcial con timedOut=true`() = runBlocking {
        val device = FakeDevice()
        device.responder = { cmd -> listOf("${FakeDevice.PROMPT} $cmd", "salida parcial...") }
        device.register("s1")
        val handler = newHandler()

        val result = handler.invoke(
            mapOf("sessionId" to "s1", "command" to "show version", "timeoutSeconds" to 1),
        )

        assertEquals(true, result["timedOut"])
        assertTrue((result["output"] as String).contains("salida parcial"))
    }

    @Test
    fun `con el setting OFF el gate decide — rechazo no ejecuta nada`() = runBlocking {
        val device = FakeDevice()
        device.register("s1")
        val gate = FakeApprovalGate().also { it.rejectAll() }
        val handler = newHandler(gate, allowWithoutApproval = false)

        val result = handler.invoke(mapOf("sessionId" to "s1", "command" to "show version"))

        assertEquals(false, result["approved"])
        assertEquals(false, result["timedOut"])
        assertTrue(device.received.isEmpty())
        assertEquals(1, gate.invocations.size)
        assertTrue(auditContains("rechazado por el operador"))
    }

    @Test
    fun `con el setting OFF la aprobacion ejecuta normalmente`() = runBlocking {
        val device = FakeDevice()
        device.register("s1")
        val gate = FakeApprovalGate().also { it.approveAll() }
        val handler = newHandler(gate, allowWithoutApproval = false)

        val result = handler.invoke(mapOf("sessionId" to "s1", "command" to "show ip route"))

        assertEquals(true, result["approved"])
        assertEquals(1, gate.invocations.size)
        assertTrue(device.received.contains("show ip route"))
    }

    @Test
    fun `edicion del operador hacia algo mutativo aborta sin ejecutar`() {
        val device = FakeDevice()
        device.register("s1")
        val gate = FakeApprovalGate().also {
            it.nextDecision = { _ ->
                ApprovalDecision.Approve(listOf("reload"), listOf(com.opentermx.ai.safety.RiskLevel.DANGEROUS))
            }
        }
        val handler = newHandler(gate, allowWithoutApproval = false)

        val ex = assertThrows(McpToolException::class.java) {
            runBlocking { handler.invoke(mapOf("sessionId" to "s1", "command" to "show version")) }
        }
        assertEquals(McpToolException.ErrorCode.INVALID_ARGUMENT, ex.code)
        assertTrue(device.received.isEmpty(), "la edición mutativa no debe ejecutarse")
    }

    @Test
    fun `timeoutSeconds fuera de rango lanza INVALID_ARGUMENT`() {
        val device = FakeDevice()
        device.register("s1")
        val handler = newHandler()
        val ex = assertThrows(McpToolException::class.java) {
            runBlocking {
                handler.invoke(mapOf("sessionId" to "s1", "command" to "show version", "timeoutSeconds" to 9999))
            }
        }
        assertEquals(McpToolException.ErrorCode.INVALID_ARGUMENT, ex.code)
        assertFalse(device.received.contains("show version"))
    }
}
