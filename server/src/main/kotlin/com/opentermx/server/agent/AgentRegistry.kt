package com.opentermx.server.agent

import com.opentermx.agent.AGENT_PROTOCOL_VERSION
import com.opentermx.agent.AgentHeartbeat
import com.opentermx.agent.AgentSessionSnapshot
import java.util.concurrent.ConcurrentHashMap

class AgentRegistry(
    private val ttlMillis: Long = 20_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val heartbeat: AgentHeartbeat, val receivedAtMillis: Long)
    private val agents = ConcurrentHashMap<String, Entry>()

    fun update(heartbeat: AgentHeartbeat) {
        require(heartbeat.protocolVersion == AGENT_PROTOCOL_VERSION) { "Versión de protocolo no soportada" }
        require(heartbeat.agentId.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "agentId inválido" }
        require(heartbeat.sessions.size <= 100) { "El agente excede 100 sesiones" }
        require(heartbeat.sessions.all { it.lines.size <= 100 }) { "Una sesión excede 100 líneas" }
        agents[heartbeat.agentId] = Entry(heartbeat, clock())
    }

    fun sessions(): List<FederatedSession> {
        evictExpired()
        return agents.values.flatMap { entry ->
            entry.heartbeat.sessions.map { FederatedSession(entry.heartbeat.agentId, entry.heartbeat.displayName, it) }
        }
    }

    fun find(federatedId: String): FederatedSession? = sessions().firstOrNull { it.id == federatedId }

    fun snapshots(): List<AgentSummary> {
        evictExpired()
        return agents.values.map { entry ->
            AgentSummary(
                entry.heartbeat.agentId,
                entry.heartbeat.displayName,
                entry.heartbeat.platform,
                entry.receivedAtMillis,
                entry.heartbeat.sessions.size,
            )
        }.sortedBy { it.agentId }
    }

    private fun evictExpired() {
        val cutoff = clock() - ttlMillis
        agents.entries.removeIf { it.value.receivedAtMillis < cutoff }
    }
}

data class AgentSummary(
    val agentId: String,
    val displayName: String,
    val platform: String,
    val lastSeenAtMillis: Long,
    val sessionCount: Int,
)

data class FederatedSession(
    val agentId: String,
    val agentName: String,
    val snapshot: AgentSessionSnapshot,
) {
    val id: String get() = "$agentId:${snapshot.sessionId}"
}
