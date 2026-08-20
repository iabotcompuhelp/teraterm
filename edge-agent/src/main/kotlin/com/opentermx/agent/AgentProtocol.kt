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
