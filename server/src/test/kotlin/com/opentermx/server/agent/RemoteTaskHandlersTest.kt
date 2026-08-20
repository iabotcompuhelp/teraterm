package com.opentermx.server.agent

import com.opentermx.agent.AgentHeartbeat
import com.opentermx.agent.AgentSessionSnapshot
import com.opentermx.agent.RemoteTaskSigner
import com.opentermx.agent.RemoteTaskStatus
import com.opentermx.mcp.operation.InMemoryOperationStore
import com.opentermx.mcp.operation.OperationContext
import com.opentermx.mcp.operation.OperationMeta
import com.opentermx.mcp.operation.OperationRegistry
import com.opentermx.mcp.operation.OperationScope
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteTaskHandlersTest {
    @Test
    fun `task is signed scoped to active operation and cancellable`() = runBlocking {
        val secret = "0123456789abcdef".toByteArray()
        val operations = OperationRegistry(InMemoryOperationStore())
        operations.start(
            "mcp-session",
            OperationContext(
                OperationMeta("op-remote", "remote check"),
                OperationScope(devices = listOf("win-01"), allowedCommandsPrefix = listOf("show")),
            ),
        )
        val agents = AgentRegistry()
        agents.update(
            AgentHeartbeat(
                agentId = "win-01", displayName = "NOC", platform = "Windows", sentAtMillis = System.currentTimeMillis(),
                sessions = listOf(AgentSessionSnapshot("ssh-1", "Core", "SSH", "10.0.0.1", 22, "ops", emptyList())),
            ),
        )
        val store = RemoteTaskStore(Files.createTempDirectory("handler-tasks"), secret)
        val created = ProposeRemoteCommandsHandler(operations, agents, store).invoke(
            mapOf("sessionId" to "win-01:ssh-1", "commands" to listOf("show clock"), "rationale" to "check"),
            "mcp-session",
        )
        val taskId = created.getValue("taskId") as String
        val task = store.task(taskId)!!
        assertEquals("op-remote", task.operationId)
        assertTrue(RemoteTaskSigner(secret).verify(task))

        val cancelled = CancelRemoteTaskHandler(operations, store).invoke(mapOf("taskId" to taskId), "mcp-session")
        assertEquals(RemoteTaskStatus.CANCELLED.name, cancelled["status"])
    }
}
