package com.opentermx.agent

enum class AgentConnectionState { DISABLED, STARTING, CONNECTED, DEGRADED, STOPPED }

data class AgentTaskHistoryEntry(
    val taskId: String,
    val status: RemoteTaskStatus,
    val completedAtMillis: Long,
    val executedCount: Int,
    val message: String?,
)

data class AgentStatus(
    val state: AgentConnectionState = AgentConnectionState.DISABLED,
    val agentId: String? = null,
    val displayName: String? = null,
    val gateway: String? = null,
    val lastHeartbeatMillis: Long? = null,
    val activeSessions: Int = 0,
    val currentTaskId: String? = null,
    val consecutiveFailures: Int = 0,
    val lastError: String? = null,
    val history: List<AgentTaskHistoryEntry> = emptyList(),
)
