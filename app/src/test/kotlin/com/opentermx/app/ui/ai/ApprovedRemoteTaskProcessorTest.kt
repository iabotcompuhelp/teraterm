package com.opentermx.app.ui.ai

import com.opentermx.agent.RemoteCommandTask
import com.opentermx.agent.RemoteTaskStatus
import com.opentermx.ai.safety.RiskLevel
import com.opentermx.common.ai.CommandSink
import com.opentermx.common.ai.SessionMetadata
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.ai.TerminalBufferProvider
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.security.ApprovalDecision
import com.opentermx.mcp.security.ApprovalGate
import com.opentermx.ai.context.Vendor
import com.opentermx.ai.safety.ClassifiedCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApprovedRemoteTaskProcessorTest {
    private val sessionId = SessionId("remote-test-session")

    @AfterEach fun cleanup() = SessionRegistry.unregister(sessionId)

    @Test
    fun `rejection never reaches command sink`() {
        val sent = mutableListOf<String>()
        register(sent)
        val gate = gateReturning(ApprovalDecision.Reject)

        val result = ApprovedRemoteTaskProcessor(gate).process(task())

        assertEquals(RemoteTaskStatus.REJECTED, result.status)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `only commands approved by operator reach session`() {
        val sent = mutableListOf<String>()
        register(sent)
        val gate = gateReturning(ApprovalDecision.Approve(listOf("show version"), listOf(RiskLevel.SAFE)))

        val result = ApprovedRemoteTaskProcessor(gate).process(task())

        assertEquals(RemoteTaskStatus.SUCCEEDED, result.status)
        assertEquals(listOf("show version"), sent)
        assertEquals(sent, result.executedCommands)
    }

    private fun register(sent: MutableList<String>) {
        SessionRegistry.register(
            sessionId,
            SessionMetadata("Core", "SSH", "10.0.0.1", 22, "ops"),
            TerminalBufferProvider { listOf("core#") },
            CommandSink { line -> sent += line; true },
        )
    }

    private fun gateReturning(decision: ApprovalDecision) = object : ApprovalGate {
        override suspend fun reviewCommands(
            prompt: String,
            vendor: Vendor,
            classifications: List<ClassifiedCommand>,
        ): ApprovalDecision = decision
    }

    private fun task() = RemoteCommandTask(
        taskId = "task-approval",
        operationId = null,
        agentId = "win-01",
        sessionId = sessionId.value,
        commands = listOf("show version", "reload"),
        rationale = "validar equipo",
        createdAtMillis = System.currentTimeMillis(),
        expiresAtMillis = System.currentTimeMillis() + 60_000,
        status = RemoteTaskStatus.DELIVERED,
    )
}
