package com.opentermx.server

import java.nio.file.Files
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HeadlessServerRuntimeTest {
    @Test
    fun `headless catalog exposes only control plane tools`() {
        val config = ServerConfig("127.0.0.1", 8765, Files.createTempDirectory("opentermx-server-test"), null)
        val names = HeadlessServerRuntime(config).use { runtime ->
            runtime.handlers.map { it.definition.name }
        }

        assertEquals(
            setOf(
                "list_sessions",
                "inspect_session",
                "start_operation",
                "current_operation",
                "resume_operation",
                "export_operation_handoff",
                "end_operation",
                "propose_remote_commands",
                "get_remote_task",
                "cancel_remote_task",
            ),
            names.toSet(),
        )
    }

    @Test
    fun `graphql endpoint shares control plane authentication`() {
        val port = ServerSocket(0).use { it.localPort }
        val token = "graphql-test-token"
        val config = ServerConfig(
            "127.0.0.1", port, Files.createTempDirectory("opentermx-graphql-http"), token,
        )
        HeadlessServerRuntime(config).use { runtime ->
            runtime.start()
            val requestBody = """{"query":"{ agents { agentId } devices { hostname } }"}"""
            val uri = URI("http://127.0.0.1:$port/graphql")
            val client = HttpClient.newHttpClient()
            val unauthorized = client.send(
                HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(401, unauthorized.statusCode())

            val authorized = client.send(
                HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, authorized.statusCode())
            assertEquals(true, authorized.body().contains("\"agents\":[]"))
            assertEquals(true, authorized.body().contains("\"devices\":[]"))
        }
    }
}
