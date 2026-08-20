package com.opentermx.server.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.opentermx.agent.AgentHeartbeat
import com.opentermx.agent.EdgeAgentConfig
import com.opentermx.agent.RemoteCommandTask
import com.opentermx.agent.RemoteTaskProcessor
import com.opentermx.agent.RemoteTaskResult
import com.opentermx.agent.RemoteTaskStatus
import com.opentermx.agent.WindowsEdgeAgent
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AgentGatewayTest {
    @Test
    fun `gateway rejects missing token and accepts authenticated heartbeat`() {
        val port = ServerSocket(0).use { it.localPort }
        val registry = AgentRegistry()
        val store = RemoteTaskStore(java.nio.file.Files.createTempDirectory("gateway-tasks"))
        AgentGateway("127.0.0.1", port, "agent-secret", registry, store).use { gateway ->
            gateway.start()
            val body = jacksonObjectMapper().writeValueAsString(
                AgentHeartbeat(
                    agentId = "win-01",
                    displayName = "NOC",
                    platform = "Windows",
                    sentAtMillis = System.currentTimeMillis(),
                    sessions = emptyList(),
                ),
            )
            val uri = URI("http://127.0.0.1:$port/agent/v1/heartbeat")
            val client = HttpClient.newHttpClient()
            val unauthorized = client.send(
                HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(401, unauthorized.statusCode())

            val accepted = client.send(
                HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer agent-secret")
                    .header("X-OpenTermX-Agent-Id", "win-01")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, accepted.statusCode())
            assertTrue(accepted.body().contains("accepted"))
        }
    }

    @Test
    fun `windows agent claims and reports a task through local gateway`() {
        val port = ServerSocket(0).use { it.localPort }
        val store = RemoteTaskStore(java.nio.file.Files.createTempDirectory("gateway-e2e-tasks"))
        val now = System.currentTimeMillis()
        store.enqueue(
            RemoteCommandTask(
                taskId = "task-e2e",
                operationId = null,
                agentId = "win-e2e",
                sessionId = "ssh-1",
                commands = listOf("show clock"),
                rationale = "prueba local",
                createdAtMillis = now,
                expiresAtMillis = now + 60_000,
            ),
        )
        val processed = CountDownLatch(1)
        val processor = RemoteTaskProcessor { task ->
            processed.countDown()
            RemoteTaskResult(task.taskId, RemoteTaskStatus.SUCCEEDED, System.currentTimeMillis(), task.commands, "ok")
        }
        AgentGateway("127.0.0.1", port, "agent-secret", AgentRegistry(), store).use { gateway ->
            gateway.start()
            val config = EdgeAgentConfig(
                URI("http://127.0.0.1:$port/agent/v1/heartbeat"),
                "agent-secret",
                "win-e2e",
                "Windows E2E",
                2,
            )
            WindowsEdgeAgent(config, taskProcessor = processor).use { agent ->
                agent.start()
                assertTrue(processed.await(5, TimeUnit.SECONDS))
                repeat(20) {
                    if (store.result("task-e2e") != null) return@repeat
                    Thread.sleep(25)
                }
                assertEquals(RemoteTaskStatus.SUCCEEDED, store.result("task-e2e")?.status)
            }
        }
    }
}
