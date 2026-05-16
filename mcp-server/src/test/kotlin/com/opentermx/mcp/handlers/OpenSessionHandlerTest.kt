package com.opentermx.mcp.handlers

import com.opentermx.mcp.security.OpenRequest
import com.opentermx.mcp.security.OpenResult
import com.opentermx.mcp.security.SessionOpener
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenSessionHandlerTest {

    private class RecordingOpener(var result: OpenResult = OpenResult.Success("sess-123", "lab1")) : SessionOpener {
        val requests = mutableListOf<OpenRequest>()
        override fun open(request: OpenRequest): OpenResult {
            requests += request
            return result
        }
    }

    @Test
    fun `operador rechaza la apertura — no invoca opener`() = runBlocking {
        val opener = RecordingOpener()
        val gate = FakeApprovalGate().also { it.rejectAll() }
        val handler = OpenSessionHandler(gate, opener)

        val out = handler.invoke(mapOf("protocol" to "SSH", "host" to "1.2.3.4", "username" to "admin"))

        assertEquals(false, out["approved"])
        assertNull(out["sessionId"])
        assertNotNull(out["error"])
        assertTrue(opener.requests.isEmpty(), "opener no debe ejecutarse si el operador rechaza")
    }

    @Test
    fun `aprobación + opener exitoso devuelve sessionId`() = runBlocking {
        val opener = RecordingOpener(OpenResult.Success("sess-xyz", "lab1"))
        val gate = FakeApprovalGate().also { it.approveAll() }
        val handler = OpenSessionHandler(gate, opener)

        val out = handler.invoke(
            mapOf(
                "protocol" to "SSH",
                "host" to "10.0.0.1",
                "port" to 2222,
                "username" to "ops",
                "credentialRef" to "kc-1",
                "label" to "lab1",
            ),
        )

        assertEquals(true, out["approved"])
        assertEquals("sess-xyz", out["sessionId"])
        assertNull(out["error"])
        val req = opener.requests.single()
        assertEquals("SSH", req.protocol)
        assertEquals("10.0.0.1", req.host)
        assertEquals(2222, req.port)
        assertEquals("ops", req.username)
        assertEquals("kc-1", req.credentialRef)
        assertEquals("lab1", req.label)
    }

    @Test
    fun `aprobación con opener que falla propaga error pedagógico`() = runBlocking {
        val opener = RecordingOpener(OpenResult.Failure("credentialRef inválido"))
        val gate = FakeApprovalGate().also { it.approveAll() }
        val handler = OpenSessionHandler(gate, opener)

        val out = handler.invoke(mapOf("protocol" to "SSH", "host" to "1.2.3.4", "username" to "admin"))

        assertEquals(true, out["approved"])
        assertNull(out["sessionId"])
        assertEquals("credentialRef inválido", out["error"])
    }

    @Test
    fun `NoOp opener (sin GUI cableada) devuelve Failure sin crashear`() = runBlocking {
        val gate = FakeApprovalGate().also { it.approveAll() }
        val handler = OpenSessionHandler(gate, SessionOpener.NoOp)

        val out = handler.invoke(mapOf("protocol" to "TELNET", "host" to "1.2.3.4"))

        assertEquals(true, out["approved"])
        assertNull(out["sessionId"])
        assertFalse((out["error"] as String).isBlank())
    }
}