package com.opentermx.server.agent

import com.opentermx.agent.AgentHeartbeat
import com.opentermx.agent.AgentSessionSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentRegistryTest {
    @Test
    fun `prefixes session ids and expires stale agents`() {
        var now = 1_000L
        val registry = AgentRegistry(ttlMillis = 20_000, clock = { now })
        registry.update(
            AgentHeartbeat(
                agentId = "win-01",
                displayName = "Consola NOC",
                platform = "Windows 11",
                sentAtMillis = now,
                sessions = listOf(
                    AgentSessionSnapshot("local-1", "Core", "SSH", "10.0.0.1", 22, "admin", listOf("core#")),
                ),
            ),
        )

        assertEquals("win-01:local-1", registry.sessions().single().id)
        now += 20_001
        assertTrue(registry.sessions().isEmpty())
    }
}
