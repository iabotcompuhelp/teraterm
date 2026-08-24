package com.opentermx.server.agent

import com.opentermx.agent.RemoteCommandTask
import com.opentermx.agent.RemoteTaskResult
import com.opentermx.agent.RemoteTaskStatus
import com.opentermx.agent.RemoteTaskSigner
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RemoteTaskStoreTest {
    @Test
    fun `queue is durable idempotent and records final result`() {
        val root = Files.createTempDirectory("remote-task-store")
        val task = task("task-1")
        val store = RemoteTaskStore(root)
        store.enqueue(task)
        store.enqueue(task)

        assertEquals(RemoteTaskStatus.DELIVERED, store.claimNext("win-01")?.status)
        val result = RemoteTaskResult("task-1", RemoteTaskStatus.SUCCEEDED, 3_000, listOf("show clock"), "12:00")
        store.complete("win-01", result)

        val recovered = RemoteTaskStore(root)
        assertEquals(RemoteTaskStatus.SUCCEEDED, recovered.task("task-1")?.status)
        assertEquals("12:00", recovered.result("task-1")?.output)
    }

    @Test
    fun `same id cannot be reused with different content`() {
        val store = RemoteTaskStore(Files.createTempDirectory("remote-task-collision"))
        store.enqueue(task("task-1"))
        assertThrows(IllegalArgumentException::class.java) {
            store.enqueue(task("task-1").copy(commands = listOf("reload")))
        }
    }

    @Test
    fun `expired lease redelivers same signed task without changing payload`() {
        var now = 1_000L
        val store = RemoteTaskStore(
            Files.createTempDirectory("remote-task-lease"),
            "0123456789abcdef".toByteArray(),
            clock = { now },
            leaseMillis = 500,
        )
        store.enqueue(task("task-lease").copy(createdAtMillis = now, expiresAtMillis = 10_000))
        val first = store.claimNext("win-01")!!
        now += 501
        val second = store.claimNext("win-01")!!

        assertEquals(first.signature, second.signature)
        assertEquals(2, second.deliveryAttempt)
    }

    @Test
    fun `each agent task uses only its individual signing secret`() {
        val secrets = mapOf(
            "win-01" to "secret-for-win-01".toByteArray(),
            "win-02" to "secret-for-win-02".toByteArray(),
        )
        val store = RemoteTaskStore(Files.createTempDirectory("per-agent-signing"), secrets::get)
        val signed = store.enqueue(task("task-agent-1"))

        assertEquals(true, RemoteTaskSigner(secrets.getValue("win-01")).verify(signed))
        assertEquals(false, RemoteTaskSigner(secrets.getValue("win-02")).verify(signed))
        assertThrows(IllegalArgumentException::class.java) {
            store.enqueue(task("task-revoked").copy(agentId = "revoked-agent"))
        }
    }

    private fun task(id: String) = RemoteCommandTask(
        taskId = id,
        operationId = "op-1",
        agentId = "win-01",
        sessionId = "ssh-1",
        commands = listOf("show clock"),
        rationale = "validación",
        createdAtMillis = System.currentTimeMillis(),
        expiresAtMillis = System.currentTimeMillis() + 60_000,
    )
}
