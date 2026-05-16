package com.opentermx.mcp.protocol

import com.opentermx.mcp.handlers.ToolHandler
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpDispatcherTest {

    private class StaticHandler(
        override val definition: ToolDef,
        private val response: Map<String, Any?>,
    ) : ToolHandler {
        var calls = 0
        override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
            calls++
            return response
        }
    }

    private val sessions = StaticHandler(
        ToolDefinitions.LIST_SESSIONS, mapOf("sessions" to emptyList<Any?>()),
    )
    private val inspect = StaticHandler(
        ToolDefinitions.INSPECT_SESSION, mapOf("lines" to listOf("hola")),
    )
    private val dispatcher = McpDispatcher(listOf(sessions, inspect))

    @Test
    fun `initialize devuelve capabilities y serverInfo`() {
        val response = dispatcher.handle(JsonRpcRequest(id = 1, method = "initialize"))!!
        assertNull(response.error)
        @Suppress("UNCHECKED_CAST")
        val result = response.result as Map<String, Any?>
        assertEquals("2024-11-05", result["protocolVersion"])
        @Suppress("UNCHECKED_CAST")
        val serverInfo = result["serverInfo"] as Map<String, Any?>
        assertNotNull(serverInfo["name"])
    }

    @Test
    fun `tools list devuelve todas las definiciones registradas`() {
        val response = dispatcher.handle(JsonRpcRequest(id = 2, method = "tools/list"))!!
        @Suppress("UNCHECKED_CAST")
        val tools = (response.result as Map<String, Any?>)["tools"] as List<Map<String, Any?>>
        assertEquals(2, tools.size)
        assertEquals(setOf("list_sessions", "inspect_session"), tools.map { it["name"] }.toSet())
    }

    @Test
    fun `tools call dispatchea y envuelve resultado en content + structuredContent`() {
        val response = dispatcher.handle(
            JsonRpcRequest(
                id = 3,
                method = "tools/call",
                params = mapOf("name" to "list_sessions", "arguments" to emptyMap<String, Any?>()),
            )
        )!!
        assertNull(response.error)
        @Suppress("UNCHECKED_CAST")
        val result = response.result as Map<String, Any?>
        assertEquals(false, result["isError"])
        assertEquals(mapOf("sessions" to emptyList<Any?>()), result["structuredContent"])
        @Suppress("UNCHECKED_CAST")
        val content = result["content"] as List<Map<String, Any?>>
        assertEquals("text", content[0]["type"])
        assertEquals(1, sessions.calls)
    }

    @Test
    fun `tools call con nombre desconocido devuelve error MCP no excepcion`() {
        val response = dispatcher.handle(
            JsonRpcRequest(
                id = 4,
                method = "tools/call",
                params = mapOf("name" to "no_existe", "arguments" to emptyMap<String, Any?>()),
            )
        )!!
        assertNull(response.result)
        assertNotNull(response.error)
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, response.error!!.code)
    }

    @Test
    fun `metodo desconocido devuelve METHOD_NOT_FOUND`() {
        val response = dispatcher.handle(JsonRpcRequest(id = 5, method = "foo/bar"))!!
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, response.error!!.code)
    }

    @Test
    fun `notification devuelve null y no es respondida`() {
        val response = dispatcher.handle(JsonRpcRequest(id = null, method = "notifications/initialized"))
        assertNull(response)
    }

    @Test
    fun `ping devuelve resultado vacío`() {
        val response = dispatcher.handle(JsonRpcRequest(id = 6, method = "ping"))!!
        assertNull(response.error)
        assertEquals(emptyMap<String, Any?>(), response.result)
        assertFalse(false) // appease unused-block
    }
}