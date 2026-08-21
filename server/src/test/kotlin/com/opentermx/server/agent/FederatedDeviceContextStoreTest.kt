package com.opentermx.server.agent

import com.opentermx.agent.AgentSessionSnapshot
import com.opentermx.agent.RemoteCommandTask
import com.opentermx.agent.RemoteTaskResult
import com.opentermx.agent.RemoteTaskStatus
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FederatedDeviceContextStoreTest {
    @Test
    fun `successful approved discovery persists attributed Aruba identity`() {
        val root = Files.createTempDirectory("federated-device-context")
        val store = FederatedDeviceContextStore(root)
        val now = System.currentTimeMillis()
        val task = RemoteCommandTask(
            taskId = "task-discovery",
            operationId = null,
            agentId = "win-01",
            sessionId = "serial-1",
            commands = listOf("show system"),
            rationale = "identificar dispositivo",
            createdAtMillis = now - 1_000,
            expiresAtMillis = now + 60_000,
        )
        val output = """
            $ show system
            Hostname             : 6300
            Product Name         : JL658A 6300M 24SFP+ 4SFP56 Swch
            ArubaOS-CX Version   : FL.10.10.1040
            Chassis Serial Nbr   : SG12345678
            Up Time              : 12 days
        """.trimIndent()
        val result = RemoteTaskResult(
            taskId = task.taskId,
            status = RemoteTaskStatus.SUCCEEDED,
            completedAtMillis = now,
            executedCommands = task.commands,
            output = output,
        )
        val session = FederatedSession(
            "win-01", "Windows",
            AgentSessionSnapshot("serial-1", "6300", "Serial", "COM6", null, null, emptyList()),
        )

        val observed = store.observe(task, result, session)
        val recovered = FederatedDeviceContextStore(root).find("win-01", "serial-1")

        assertNotNull(observed)
        assertEquals("JL658A 6300M 24SFP+ 4SFP56 Swch", recovered?.model)
        assertEquals("FL.10.10.1040", recovered?.osVersion)
        assertEquals(listOf("SG12345678"), recovered?.serialNumbers)
        assertEquals("show system", recovered?.sourceCommand)
        assertTrue(recovered!!.allowedDiscoveryCommands.contains("show interfaces brief"))
    }
}
