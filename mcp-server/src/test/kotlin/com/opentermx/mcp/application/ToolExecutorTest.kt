package com.opentermx.mcp.application

import com.opentermx.mcp.handlers.ToolHandler
import com.opentermx.mcp.protocol.JsonRpcRequest
import com.opentermx.mcp.protocol.McpDispatcher
import com.opentermx.mcp.protocol.TransportContext
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions
import com.opentermx.mcp.operation.InMemoryOperationStore
import com.opentermx.mcp.operation.OperationContext
import com.opentermx.mcp.operation.OperationMeta
import com.opentermx.mcp.operation.OperationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolExecutorTest {

    private class RecordingHandler(
        override val definition: ToolDef,
    ) : ToolHandler {
        val calls = mutableListOf<Map<String, Any?>>()

        override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
            calls += args
            return mapOf("source" to "application", "arguments" to args)
        }
    }

    @Test
    fun `internal entry and MCP transport use the same executor`() = runBlocking {
        val handler = RecordingHandler(ToolDefinitions.LIST_SESSIONS)
        val executor = ToolExecutor(listOf(handler))

        val internal = executor.execute("list_sessions", mapOf("origin" to "chat"), ToolExecutionContext.internalChat())
        assertTrue(internal is ToolExecutionResult.Success)

        val dispatcher = McpDispatcher(listOf(handler), toolExecutor = executor)
        val response = dispatcher.handle(
            JsonRpcRequest(
                id = 1,
                method = "tools/call",
                params = mapOf("name" to "list_sessions", "arguments" to mapOf("origin" to "mcp")),
            ),
            TransportContext.test(),
        )!!

        assertEquals(null, response.error)
        assertEquals(listOf("chat", "mcp"), handler.calls.map { it["origin"] })
    }

    @Test
    fun `read-only context blocks mutation before handler`() = runBlocking {
        val handler = RecordingHandler(ToolDefinitions.PROPOSE_COMMANDS)
        val executor = ToolExecutor(listOf(handler))

        val result = executor.execute(
            "propose_commands",
            mapOf("sessionId" to "s1", "commands" to listOf("show version"), "rationale" to "test"),
            ToolExecutionContext("internal", forceReadOnly = true),
        )

        assertTrue(result is ToolExecutionResult.Rejected)
        assertEquals(ToolRejection.FORBIDDEN, (result as ToolExecutionResult.Rejected).reason)
        assertTrue(handler.calls.isEmpty())
    }

    @Test
    fun `tool calls quedan en journal con correlation id`() = runBlocking {
        val store = InMemoryOperationStore()
        val registry = OperationRegistry(store)
        val operation = registry.start(
            "internal-chat",
            OperationContext(operation = OperationMeta(id = "op-journal", description = "test")),
        )
        val handler = RecordingHandler(ToolDefinitions.LIST_SESSIONS)
        val executor = ToolExecutor(listOf(handler), operationRegistry = registry)

        val result = executor.execute(
            "list_sessions",
            mapOf("apiToken" to "do-not-store"),
            ToolExecutionContext("internal-chat", correlationId = "corr-fixed"),
        )

        assertTrue(result is ToolExecutionResult.Success)
        val events = registry.journal(operation.operationId)
        assertEquals(listOf("TOOL_STARTED", "TOOL_SUCCEEDED"), events.takeLast(2).map { it.type.name })
        assertEquals(listOf("corr-fixed", "corr-fixed"), events.takeLast(2).map { it.correlationId })
        @Suppress("UNCHECKED_CAST")
        val arguments = events[events.lastIndex - 1].payload["arguments"] as Map<String, Any?>
        assertEquals("[REDACTED]", arguments["apiToken"])
    }
}
