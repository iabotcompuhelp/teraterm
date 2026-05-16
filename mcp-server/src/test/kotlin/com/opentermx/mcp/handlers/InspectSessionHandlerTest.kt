package com.opentermx.mcp.handlers

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class InspectSessionHandlerTest {

    private val handler = InspectSessionHandler()

    @AfterEach
    fun cleanup() = TestFixtures.unregisterAll()

    @Test
    fun `404 cuando la sesión no existe`() {
        val ex = assertThrows(McpToolException::class.java) {
            runBlocking { handler.invoke(mapOf("sessionId" to "ghost")) }
        }
        assertEquals(McpToolException.ErrorCode.NOT_FOUND, ex.code)
    }

    @Test
    fun `argumento sessionId obligatorio`() {
        val ex = assertThrows(McpToolException::class.java) {
            runBlocking { handler.invoke(emptyMap()) }
        }
        assertEquals(McpToolException.ErrorCode.INVALID_ARGUMENT, ex.code)
    }

    @Test
    fun `default lastLines 50 se respeta y limita al buffer real`() = runBlocking {
        val lines = (1..120).map { "line$it" }
        TestFixtures.registerSession(idValue = "s1", protocol = "SSH", buffer = lines)
        val result = handler.invoke(mapOf("sessionId" to "s1"))
        @Suppress("UNCHECKED_CAST")
        val returned = result["lines"] as List<String>
        assertEquals(50, returned.size)
        assertEquals("line71", returned.first())
        assertEquals("line120", returned.last())
        assertEquals("SSH", result["protocol"])
    }

    @Test
    fun `lastLines fuera de rango lanza INVALID_ARGUMENT`() {
        TestFixtures.registerSession(idValue = "s1", protocol = "SSH")
        val ex = assertThrows(McpToolException::class.java) {
            runBlocking { handler.invoke(mapOf("sessionId" to "s1", "lastLines" to 9999)) }
        }
        assertEquals(McpToolException.ErrorCode.INVALID_ARGUMENT, ex.code)
    }
}