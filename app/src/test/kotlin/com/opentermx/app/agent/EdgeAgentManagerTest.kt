package com.opentermx.app.agent

import com.opentermx.agent.AgentConnectionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class EdgeAgentManagerTest {
    @Test
    fun `unconfigured manager stays disabled and cannot start`() {
        EdgeAgentManager.configure(null, null)

        assertFalse(EdgeAgentManager.start())
        assertEquals(AgentConnectionState.DISABLED, EdgeAgentManager.status().value.state)
    }
}
