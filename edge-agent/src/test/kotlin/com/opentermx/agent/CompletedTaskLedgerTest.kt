package com.opentermx.agent

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CompletedTaskLedgerTest {
    @Test
    fun `completed result survives agent restart`() {
        val path = Files.createTempDirectory("agent-ledger").resolve("completed.jsonl")
        val result = RemoteTaskResult("task-1", RemoteTaskStatus.SUCCEEDED, 10, listOf("show clock"), "ok")
        CompletedTaskLedger(path).record(result)

        assertEquals(result, CompletedTaskLedger(path).result("task-1"))
    }
}
