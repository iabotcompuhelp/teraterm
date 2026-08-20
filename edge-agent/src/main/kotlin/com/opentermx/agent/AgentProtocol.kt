package com.opentermx.agent

const val AGENT_PROTOCOL_VERSION = 1

data class AgentHeartbeat(
    val protocolVersion: Int = AGENT_PROTOCOL_VERSION,
    val agentId: String,
    val displayName: String,
    val platform: String,
    val sentAtMillis: Long,
    val sessions: List<AgentSessionSnapshot>,
)

data class AgentSessionSnapshot(
    val sessionId: String,
    val name: String,
    val protocol: String,
    val host: String?,
    val port: Int?,
    val username: String?,
    val lines: List<String>,
)

data class AgentHeartbeatAck(
    val accepted: Boolean,
    val serverTimeMillis: Long,
    val nextHeartbeatSeconds: Int,
)

enum class RemoteTaskStatus { PENDING, DELIVERED, APPROVED, REJECTED, RUNNING, SUCCEEDED, FAILED, EXPIRED, CANCELLED }

data class RemoteCommandTask(
    val protocolVersion: Int = AGENT_PROTOCOL_VERSION,
    val taskId: String,
    val operationId: String?,
    val agentId: String,
    val sessionId: String,
    val commands: List<String>,
    val rationale: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val status: RemoteTaskStatus = RemoteTaskStatus.PENDING,
    val leaseExpiresAtMillis: Long? = null,
    val deliveryAttempt: Int = 0,
    val signature: String? = null,
)

data class RemoteTaskResult(
    val taskId: String,
    val status: RemoteTaskStatus,
    val completedAtMillis: Long,
    val executedCommands: List<String> = emptyList(),
    val output: String? = null,
    val error: String? = null,
)

fun interface RemoteTaskProcessor {
    fun process(task: RemoteCommandTask): RemoteTaskResult

    companion object {
        val RejectUnavailable = RemoteTaskProcessor { task ->
            RemoteTaskResult(
                taskId = task.taskId,
                status = RemoteTaskStatus.REJECTED,
                completedAtMillis = System.currentTimeMillis(),
                error = "La aprobación humana no está configurada en este cliente",
            )
        }
    }
}
