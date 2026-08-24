package com.opentermx.telemetrydb

import org.slf4j.LoggerFactory

class AgentRepository internal constructor(private val db: TelemetryDb) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun observe(
        agentId: String,
        displayName: String,
        platform: String,
        agentVersion: String?,
        protocolVersion: Int,
        remoteAddress: String?,
        sessionCount: Int,
    ): Boolean = runCatching {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO agents (
                  agent_id, display_name, platform, agent_version, protocol_version,
                  last_remote_addr, last_session_count
                ) VALUES (?, ?, ?, ?, ?, ?::inet, ?)
                ON CONFLICT (agent_id) DO UPDATE SET
                  display_name=EXCLUDED.display_name, platform=EXCLUDED.platform,
                  agent_version=EXCLUDED.agent_version, protocol_version=EXCLUDED.protocol_version,
                  last_remote_addr=EXCLUDED.last_remote_addr, last_session_count=EXCLUDED.last_session_count,
                  last_seen_at=now()
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, agentId); ps.setString(2, displayName); ps.setString(3, platform)
                ps.setString(4, agentVersion); ps.setInt(5, protocolVersion)
                ps.setString(6, inetOrNull(remoteAddress)); ps.setInt(7, sessionCount)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO agent_auth_events (agent_id, remote_addr, event_type, outcome) " +
                    "VALUES (?, ?::inet, 'AUTHENTICATED', 'SUCCESS')",
            ).use { ps ->
                ps.setString(1, agentId); ps.setString(2, inetOrNull(remoteAddress)); ps.executeUpdate()
            }
        }
        true
    }.onFailure { log.warn("No se pudo persistir heartbeat de agente {}: {}", agentId, it.message) }
        .getOrDefault(false)

    fun recordDenied(agentId: String?, remoteAddress: String?, eventType: String, detail: String? = null): Boolean =
        runCatching {
            require(eventType in setOf("AUTH_FAILED", "IDENTITY_MISMATCH", "REVOKED"))
            db.withConnection { conn ->
                conn.prepareStatement(
                    "INSERT INTO agent_auth_events (agent_id, remote_addr, event_type, outcome, detail) " +
                        "VALUES (?, ?::inet, ?, 'DENIED', ?)",
                ).use { ps ->
                    ps.setString(1, agentId?.take(64)); ps.setString(2, inetOrNull(remoteAddress))
                    ps.setString(3, eventType); ps.setString(4, detail?.take(256)); ps.executeUpdate()
                }
            }
            true
        }.onFailure { log.warn("No se pudo persistir rechazo de agente: {}", it.message) }.getOrDefault(false)

    fun recordCredentialLifecycle(agentId: String, eventType: String): Boolean = runCatching {
        require(eventType in setOf("ENROLLED", "ROTATED", "REVOKED"))
        db.withConnection { conn ->
            if (eventType == "REVOKED") {
                conn.prepareStatement("UPDATE agents SET enabled=false WHERE agent_id=?").use {
                    it.setString(1, agentId); it.executeUpdate()
                }
            } else {
                conn.prepareStatement("UPDATE agents SET enabled=true WHERE agent_id=?").use {
                    it.setString(1, agentId); it.executeUpdate()
                }
            }
            conn.prepareStatement(
                "INSERT INTO agent_auth_events (agent_id, event_type, outcome) VALUES (?, ?, 'SUCCESS')",
            ).use { ps -> ps.setString(1, agentId); ps.setString(2, eventType); ps.executeUpdate() }
        }
        true
    }.onFailure { log.warn("No se pudo persistir ciclo de credencial {}: {}", agentId, it.message) }
        .getOrDefault(false)

    fun list(limit: Int = 100): List<Map<String, Any?>> = runCatching {
        db.withConnection { conn ->
            conn.queryToMaps(
                "SELECT agent_id AS \"agentId\", display_name AS \"displayName\", platform, " +
                    "agent_version AS \"agentVersion\", protocol_version AS \"protocolVersion\", " +
                    "host(last_remote_addr) AS \"lastRemoteAddress\", first_seen_at AS \"firstSeenAt\", " +
                    "last_seen_at AS \"lastSeenAt\", last_session_count AS \"sessionCount\", enabled " +
                    "FROM agents ORDER BY last_seen_at DESC LIMIT ?",
            ) { it.setInt(1, limit.coerceIn(1, 500)) }
        }
    }.getOrDefault(emptyList())
}
