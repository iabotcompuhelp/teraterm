package com.opentermx.server.agent

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentCredentialsTest {
    @Test
    fun `isolates agents and reloads revocation`() {
        val file = Files.createTempFile("agent-credentials", ".properties")
        file.writeText("agent-a=token-for-agent-a-123\nagent-b=token-for-agent-b-123\n")
        val lifecycle = mutableListOf<Pair<String, String>>()
        val credentials = FileAgentCredentials(file) { agentId, event -> lifecycle += agentId to event }

        assertTrue(credentials.authenticate("agent-a", "token-for-agent-a-123"))
        assertFalse(credentials.authenticate("agent-a", "token-for-agent-b-123"))
        assertTrue(credentials.authenticate("agent-b", "token-for-agent-b-123"))
        assertTrue(lifecycle.contains("agent-a" to "ENROLLED"))
        assertTrue(lifecycle.contains("agent-b" to "ENROLLED"))

        file.writeText("agent-b=rotated-agent-b-token\n")
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 2_000))
        assertFalse(credentials.authenticate("agent-a", "token-for-agent-a-123"))
        assertFalse(credentials.authenticate("agent-b", "token-for-agent-b-123"))
        assertTrue(credentials.authenticate("agent-b", "rotated-agent-b-token"))
        assertTrue(lifecycle.contains("agent-a" to "REVOKED"))
        assertTrue(lifecycle.contains("agent-b" to "ROTATED"))
    }
}
