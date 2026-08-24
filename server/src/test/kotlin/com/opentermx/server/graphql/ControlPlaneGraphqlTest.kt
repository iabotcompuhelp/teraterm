package com.opentermx.server.graphql

import com.opentermx.agent.RemoteCommandTask
import com.opentermx.agent.RemoteTaskStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ControlPlaneGraphqlTest {
    @Test
    fun `queries several agents without exposing task commands`() {
        val task = RemoteCommandTask(
            taskId = "task-1",
            operationId = "op-1",
            agentId = "agent-a",
            sessionId = "serial-1",
            commands = listOf("configure terminal", "password should-not-leak"),
            rationale = "internal rationale",
            createdAtMillis = 100,
            expiresAtMillis = 200,
            status = RemoteTaskStatus.PENDING,
        )
        val gateway = ControlPlaneGraphql(
            database = { null },
            agents = {
                listOf(
                    mapOf("agentId" to "agent-a", "displayName" to "NOC A", "platform" to "Windows", "sessionCount" to 2),
                    mapOf("agentId" to "agent-b", "displayName" to "NOC B", "platform" to "Linux", "sessionCount" to 1),
                )
            },
            tasks = { agentId, _ -> listOf(task).filter { agentId == null || it.agentId == agentId } },
        )

        val result = gateway.execute(
            """
            query(${ '$' }agent: String!) {
              agents { agentId displayName sessionCount }
              remoteTasks(agentId: ${ '$' }agent) { taskId agentId status }
            }
            """.trimIndent(),
            mapOf("agent" to "agent-a"),
        )

        assertFalse(result.containsKey("errors"), result.toString())
        val data = result["data"] as Map<*, *>
        assertEquals(2, (data["agents"] as List<*>).size)
        assertEquals("task-1", ((data["remoteTasks"] as List<*>).first() as Map<*, *>)["taskId"])
        assertFalse(result.toString().contains("should-not-leak"))
        assertFalse(result.toString().contains("internal rationale"))
    }

    @Test
    fun `returns empty inventory while database is disabled`() {
        val gateway = ControlPlaneGraphql({ null }, { emptyList() }, { _, _ -> emptyList() })
        val result = gateway.execute("{ devices { id hostname } deviceActivity(hostname: \"sw1\") { summary } }")

        assertFalse(result.containsKey("errors"), result.toString())
        val data = result["data"] as Map<*, *>
        assertEquals(emptyList<Any>(), data["devices"])
        assertEquals(emptyList<Any>(), data["deviceActivity"])
    }
}
