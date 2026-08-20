package com.opentermx.server.agent

import com.opentermx.agent.AgentHeartbeat
import com.opentermx.agent.AgentSessionSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FederatedSessionHandlersTest {
    @Test
    fun `remote sessions are visible and inspectable through mcp handlers`() = runBlocking {
        val registry = AgentRegistry()
        registry.update(
            AgentHeartbeat(
                agentId = "win-01",
                displayName = "NOC",
                platform = "Windows",
                sentAtMillis = System.currentTimeMillis(),
                sessions = listOf(
                    AgentSessionSnapshot("ssh-1", "Core", "SSH", "10.0.0.1", 22, "ops", listOf("line 1", "core#")),
                ),
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val listed = FederatedListSessionsHandler(registry).invoke(emptyMap())["sessions"] as List<Map<String, Any?>>
        assertTrue(listed.any { it["sessionId"] == "win-01:ssh-1" })

        val inspected = FederatedInspectSessionHandler(registry).invoke(
            mapOf("sessionId" to "win-01:ssh-1", "lastLines" to 1),
        )
        assertEquals(listOf("core#"), inspected["lines"])
        assertEquals("SSH", inspected["protocol"])
    }
}
