package com.opentermx.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EdgeAgentConfigTest {
    @Test
    fun `agent stays disabled without control plane url`() {
        assertNull(EdgeAgentConfig.fromEnvironment(emptyMap()))
    }

    @Test
    fun `configured agent requires token`() {
        assertThrows(IllegalArgumentException::class.java) {
            EdgeAgentConfig.fromEnvironment(mapOf("OPENTERMX_CONTROL_PLANE_URL" to "http://server:8766"))
        }
    }

    @Test
    fun `builds heartbeat endpoint from server url`() {
        val config = EdgeAgentConfig.fromEnvironment(
            mapOf(
                "OPENTERMX_CONTROL_PLANE_URL" to "https://server.example:8766/",
                "OPENTERMX_AGENT_TOKEN" to "secret",
                "OPENTERMX_AGENT_ID" to "win-console-01",
            ),
        )!!

        assertEquals("https://server.example:8766/agent/v1/heartbeat", config.gatewayUri.toString())
        assertEquals("win-console-01", config.agentId)
    }
}
