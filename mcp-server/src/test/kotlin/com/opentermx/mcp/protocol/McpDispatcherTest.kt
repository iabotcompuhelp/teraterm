package com.opentermx.mcp.protocol

import com.opentermx.mcp.handlers.ToolHandler
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
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

    /** Helper para tests que necesitan version negotiation: inicializa y devuelve un context válido. */
    private fun initializedTransport(version: String = "2024-11-05"): TransportContext {
        dispatcher.handle(
            JsonRpcRequest(id = 1, method = "initialize", params = mapOf("protocolVersion" to version)),
            transport = TransportContext(sessionKey = "ut", protocolVersionHeader = null, enforceVersionHeader = true),
        )
        return TransportContext(sessionKey = "ut", protocolVersionHeader = version, enforceVersionHeader = true)
    }

    @Test
    fun `initialize devuelve capabilities y serverInfo`() {
        val response = dispatcher.handle(JsonRpcRequest(id = 1, method = "initialize"))!!
        assertNull(response.error)
        @Suppress("UNCHECKED_CAST")
        val result = response.result as Map<String, Any?>
        // Sin protocolVersion en params, devuelve la más compat (la primera soportada).
        assertEquals(McpDispatcher.SUPPORTED_VERSIONS.first(), result["protocolVersion"])
        @Suppress("UNCHECKED_CAST")
        val serverInfo = result["serverInfo"] as Map<String, Any?>
        assertNotNull(serverInfo["name"])
    }

    @Test
    fun `initialize respeta la version solicitada si esta soportada`() {
        val response = dispatcher.handle(
            JsonRpcRequest(id = 1, method = "initialize", params = mapOf("protocolVersion" to "2024-11-05")),
        )!!
        @Suppress("UNCHECKED_CAST")
        val result = response.result as Map<String, Any?>
        assertEquals("2024-11-05", result["protocolVersion"])
    }

    @Test
    fun `initialize con version desconocida fallback a la ultima soportada`() {
        val response = dispatcher.handle(
            JsonRpcRequest(id = 1, method = "initialize", params = mapOf("protocolVersion" to "1999-01-01")),
        )!!
        @Suppress("UNCHECKED_CAST")
        val result = response.result as Map<String, Any?>
        assertEquals(dispatcher.latestProtocolVersion, result["protocolVersion"])
    }

    @Test
    fun `tools list rechaza request sin MCP-Protocol-Version cuando enforce true`() {
        val transport = initializedTransport()
        val sinHeader = transport.copy(protocolVersionHeader = null)
        val response = dispatcher.handle(JsonRpcRequest(id = 2, method = "tools/list"), sinHeader)!!
        assertEquals(JsonRpcError.INVALID_REQUEST, response.error!!.code)
        assertTrue(response.error!!.message.contains("MCP-Protocol-Version"))
    }

    @Test
    fun `tools list rechaza request con version distinta a la negociada`() {
        val transport = initializedTransport(version = "2024-11-05")
        val mismatch = transport.copy(protocolVersionHeader = "2025-03-26")
        val response = dispatcher.handle(JsonRpcRequest(id = 2, method = "tools/list"), mismatch)!!
        assertEquals(JsonRpcError.INVALID_REQUEST, response.error!!.code)
        assertTrue(response.error!!.message.contains("no coincide"))
    }

    @Test
    fun `tools list rechaza request previo a initialize`() {
        val transport = TransportContext("nuevo-cliente", "2024-11-05", enforceVersionHeader = true)
        val response = dispatcher.handle(JsonRpcRequest(id = 2, method = "tools/list"), transport)!!
        assertEquals(JsonRpcError.INVALID_REQUEST, response.error!!.code)
        assertTrue(response.error!!.message.contains("no inicializado"))
    }

    @Test
    fun `tools list devuelve todas las definiciones registradas con header valido`() {
        val transport = initializedTransport()
        val response = dispatcher.handle(JsonRpcRequest(id = 2, method = "tools/list"), transport)!!
        @Suppress("UNCHECKED_CAST")
        val tools = (response.result as Map<String, Any?>)["tools"] as List<Map<String, Any?>>
        assertEquals(2, tools.size)
        assertEquals(setOf("list_sessions", "inspect_session"), tools.map { it["name"] }.toSet())
    }

    @Test
    fun `tools call dispatchea y envuelve resultado en content + structuredContent`() {
        val transport = initializedTransport()
        val response = dispatcher.handle(
            JsonRpcRequest(
                id = 3,
                method = "tools/call",
                params = mapOf("name" to "list_sessions", "arguments" to emptyMap<String, Any?>()),
            ),
            transport,
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
        val transport = initializedTransport()
        val response = dispatcher.handle(
            JsonRpcRequest(
                id = 4,
                method = "tools/call",
                params = mapOf("name" to "no_existe", "arguments" to emptyMap<String, Any?>()),
            ),
            transport,
        )!!
        assertNull(response.result)
        assertNotNull(response.error)
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, response.error!!.code)
    }

    @Test
    fun `metodo desconocido devuelve METHOD_NOT_FOUND`() {
        val transport = initializedTransport()
        val response = dispatcher.handle(JsonRpcRequest(id = 5, method = "foo/bar"), transport)!!
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, response.error!!.code)
    }

    @Test
    fun `notification devuelve null y no es respondida`() {
        val response = dispatcher.handle(JsonRpcRequest(id = null, method = "notifications/initialized"))
        assertNull(response)
    }

    @Test
    fun `ping no requiere header de version`() {
        val transport = TransportContext("anon", null, enforceVersionHeader = true)
        val response = dispatcher.handle(JsonRpcRequest(id = 6, method = "ping"), transport)!!
        assertNull(response.error)
        assertEquals(emptyMap<String, Any?>(), response.result)
    }

    @Test
    fun `read-only mode bloquea tools mutativas con method_not_found`() {
        // ToolDefinitions.PROPOSE_COMMANDS es mutating=true; inyectamos un handler stub.
        val mutating = StaticHandler(
            ToolDefinitions.PROPOSE_COMMANDS, mapOf("approved" to true),
        )
        val ro = McpDispatcher(listOf(mutating), readOnly = true)
        ro.handle(JsonRpcRequest(id = 1, method = "initialize", params = mapOf("protocolVersion" to "2024-11-05")))
        val transport = TransportContext("test", "2024-11-05", enforceVersionHeader = true)
        val response = ro.handle(
            JsonRpcRequest(
                id = 2,
                method = "tools/call",
                params = mapOf(
                    "name" to "propose_commands",
                    "arguments" to mapOf("sessionId" to "s1", "commands" to listOf("show version")),
                ),
            ),
            transport,
        )!!
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, response.error!!.code)
        assertTrue(response.error!!.message.contains("read-only"))
    }

    @Test
    fun `ACL glob bloquea sessionId fuera de scope`() {
        val tools = StaticHandler(ToolDefinitions.INSPECT_SESSION, mapOf("lines" to emptyList<String>()))
        val restricted = McpDispatcher(listOf(tools), allowedSessionGlob = "lab-*")
        restricted.handle(JsonRpcRequest(id = 1, method = "initialize", params = mapOf("protocolVersion" to "2024-11-05")))
        val transport = TransportContext("test", "2024-11-05", enforceVersionHeader = true)
        val response = restricted.handle(
            JsonRpcRequest(
                id = 2,
                method = "tools/call",
                params = mapOf(
                    "name" to "inspect_session",
                    "arguments" to mapOf("sessionId" to "prod-x", "lastLines" to 10),
                ),
            ),
            transport,
        )!!
        assertEquals(JsonRpcError.INVALID_PARAMS, response.error!!.code)
        assertTrue(response.error!!.message.contains("fuera del scope"))
    }
}