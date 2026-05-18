package com.opentermx.mcp.operation

import com.opentermx.mcp.handlers.OperationAwareToolHandler
import com.opentermx.mcp.handlers.StartOperationHandler
import com.opentermx.mcp.handlers.ToolHandler
import com.opentermx.mcp.protocol.JsonRpcRequest
import com.opentermx.mcp.protocol.McpDispatcher
import com.opentermx.mcp.protocol.TransportContext
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DispatcherOperationInjectionTest {

    /**
     * Handler de stub que pretende ser `propose_commands` para que el dispatcher pueda
     * encontrar una tool con name "propose_commands" sin levantar todo el stack de IA.
     * Devuelve cualquier args como payload — los tests inspeccionan el resultado wrappeado.
     */
    private class EchoProposeHandler : ToolHandler {
        override val definition: ToolDef = ToolDefinitions.PROPOSE_COMMANDS
        override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> =
            linkedMapOf("echo" to args.toString())
    }

    /**
     * Handler de stub que devuelve el sessionKey que recibió — para verificar que
     * OperationAwareToolHandler.invoke(args, sessionKey) lo recibe correctamente.
     */
    private class SessionKeyEchoHandler : OperationAwareToolHandler {
        override val definition: ToolDef = ToolDefinitions.CURRENT_OPERATION
        override suspend fun invoke(args: Map<String, Any?>, sessionKey: String): Map<String, Any?> =
            linkedMapOf("sessionKey" to sessionKey)
    }

    private fun initializedTransport(sessionKey: String): TransportContext {
        return TransportContext(sessionKey = sessionKey, protocolVersionHeader = "2024-11-05", enforceVersionHeader = false)
    }

    @Test
    fun `dispatcher inyecta el bloque cuando hay op activa para esa sessionKey`() {
        val registry = OperationRegistry(InMemoryOperationStore())
        registry.start(
            "session-A",
            OperationContext(
                operation = OperationMeta(id = "op-inject", description = "test inject"),
                scope = OperationScope(forbiddenCommands = listOf("reload")),
            ),
        )
        val dispatcher = McpDispatcher(
            handlers = listOf(EchoProposeHandler()),
            operationRegistry = registry,
        )
        val response = dispatcher.handle(
            JsonRpcRequest(
                id = 1,
                method = "tools/call",
                params = mapOf("name" to "propose_commands", "arguments" to mapOf("sessionId" to "x", "commands" to listOf("show version"))),
            ),
            transport = initializedTransport("session-A"),
        )!!
        @Suppress("UNCHECKED_CAST")
        val result = response.result as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val content = result["content"] as List<Map<String, Any?>>
        val text = content[0]["text"] as String
        assertTrue(text.contains("[OPERATION CONTEXT op-inject]"), "text real: $text")
    }

    @Test
    fun `sin op activa, el dispatcher no toca el content`() {
        val registry = OperationRegistry(InMemoryOperationStore())
        val dispatcher = McpDispatcher(
            handlers = listOf(EchoProposeHandler()),
            operationRegistry = registry,
        )
        val response = dispatcher.handle(
            JsonRpcRequest(
                id = 1, method = "tools/call",
                params = mapOf("name" to "propose_commands", "arguments" to mapOf("commands" to listOf("show ver"))),
            ),
            transport = initializedTransport("session-A"),
        )!!
        @Suppress("UNCHECKED_CAST")
        val text = ((response.result as Map<String, Any?>)["content"] as List<Map<String, Any?>>)[0]["text"] as String
        assertTrue(!text.contains("[OPERATION CONTEXT"), "no debería tener bloque; text: $text")
    }

    @Test
    fun `forbidden_commands bloquea ANTES de invocar el handler`() {
        val registry = OperationRegistry(InMemoryOperationStore())
        registry.start(
            "session-A",
            OperationContext(
                operation = OperationMeta(id = "op-block", description = "x"),
                scope = OperationScope(forbiddenCommands = listOf("reload")),
            ),
        )
        val echoHandler = EchoProposeHandler()
        val dispatcher = McpDispatcher(
            handlers = listOf(echoHandler),
            operationRegistry = registry,
        )
        val response = dispatcher.handle(
            JsonRpcRequest(
                id = 1, method = "tools/call",
                params = mapOf(
                    "name" to "propose_commands",
                    "arguments" to mapOf("commands" to listOf("show version", "reload")),
                ),
            ),
            transport = initializedTransport("session-A"),
        )!!
        @Suppress("UNCHECKED_CAST")
        val result = response.result as Map<String, Any?>
        assertEquals(true, result["isError"])
        @Suppress("UNCHECKED_CAST")
        val text = (result["content"] as List<Map<String, Any?>>)[0]["text"] as String
        assertTrue(text.contains("forbidden_commands"), "text real: $text")
    }

    @Test
    fun `OperationAwareToolHandler recibe el sessionKey del transport`() {
        val dispatcher = McpDispatcher(
            handlers = listOf(SessionKeyEchoHandler()),
        )
        val response = dispatcher.handle(
            JsonRpcRequest(id = 1, method = "tools/call",
                params = mapOf("name" to "current_operation", "arguments" to emptyMap<String, Any?>())),
            transport = initializedTransport("real-session-key"),
        )!!
        @Suppress("UNCHECKED_CAST")
        val payload = (response.result as Map<String, Any?>)["structuredContent"] as Map<String, Any?>
        assertEquals("real-session-key", payload["sessionKey"])
    }

    @Test
    fun `start_operation handler crea la op y devuelve el id`() {
        val registry = OperationRegistry(InMemoryOperationStore())
        val dispatcher = McpDispatcher(
            handlers = listOf(StartOperationHandler(registry)),
            operationRegistry = registry,
        )
        val response = dispatcher.handle(
            JsonRpcRequest(id = 1, method = "tools/call",
                params = mapOf(
                    "name" to "start_operation",
                    "arguments" to mapOf(
                        "contextInline" to mapOf(
                            "operation" to mapOf("description" to "via dispatcher"),
                            "scope" to emptyMap<String, Any?>(),
                        ),
                    ),
                )),
            transport = initializedTransport("session-X"),
        )!!
        @Suppress("UNCHECKED_CAST")
        val payload = (response.result as Map<String, Any?>)["structuredContent"] as Map<String, Any?>
        val opId = payload["operationId"] as String
        assertNotNull(registry.forOperationId(opId))
        assertEquals(opId, registry.forSessionKey("session-X")?.operationId)
    }
}
