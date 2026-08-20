package com.opentermx.agent

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteTaskSignerTest {
    private val signer = RemoteTaskSigner("0123456789abcdef".toByteArray())

    @Test
    fun `signature survives lease changes but rejects payload tampering`() {
        val signed = signer.sign(task())
        assertTrue(signer.verify(signed.copy(status = RemoteTaskStatus.DELIVERED, leaseExpiresAtMillis = 9_000, deliveryAttempt = 2)))
        assertFalse(signer.verify(signed.copy(commands = listOf("reload"))))
        assertFalse(RemoteTaskSigner("fedcba9876543210".toByteArray()).verify(signed))
    }

    private fun task() = RemoteCommandTask(
        taskId = "task-sign", operationId = "op-1", agentId = "win-1", sessionId = "ssh-1",
        commands = listOf("show clock"), rationale = "check", createdAtMillis = 1, expiresAtMillis = 10_000,
    )
}
