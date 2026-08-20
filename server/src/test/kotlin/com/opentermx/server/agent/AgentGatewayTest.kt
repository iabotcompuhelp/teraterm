package com.opentermx.server.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.opentermx.agent.AgentHeartbeat
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentGatewayTest {
    @Test
    fun `gateway rejects missing token and accepts authenticated heartbeat`() {
        val port = ServerSocket(0).use { it.localPort }
        val registry = AgentRegistry()
        AgentGateway("127.0.0.1", port, "agent-secret", registry).use { gateway ->
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
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, accepted.statusCode())
            assertTrue(accepted.body().contains("accepted"))
        }
    }
}
