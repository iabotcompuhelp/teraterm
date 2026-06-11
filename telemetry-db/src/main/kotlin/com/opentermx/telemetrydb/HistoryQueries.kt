package com.opentermx.telemetrydb

import java.time.OffsetDateTime
import org.slf4j.LoggerFactory

/**
 * Consultas de histórico para la tool MCP `get_device_history`. Todas filtran por
 * hostname (case-insensitive), rango temporal opcional y `limit` server-side (error
 * #26: nunca devolver "todo"). El SQL es estático con binds — los fragmentos
 * condicionales son constantes, jamás input del cliente.
 */
class HistoryQueries internal constructor(private val db: TelemetryDb) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun interfaceMetrics(
        hostname: String,
        interfaceName: String?,
        from: OffsetDateTime?,
        to: OffsetDateTime?,
        limit: Int,
    ): List<Map<String, Any?>> = safeQuery {
        val sql = buildString {
            append(
                """
                SELECT m.time, i.name AS interface, m.oper_status::text AS oper_status,
                       m.input_rate_bps, m.output_rate_bps, m.input_packets, m.output_packets,
                       m.input_errors, m.output_errors, m.crc_errors, m.input_drops, m.output_drops,
                       m.utilization_in_pct, m.utilization_out_pct, m.collection_method
                FROM interface_metrics m
                JOIN interfaces i ON i.id = m.interface_id
                JOIN devices d ON d.id = i.device_id
                WHERE lower(d.hostname) = lower(?)
                """.trimIndent()
            )
            if (interfaceName != null) append(" AND i.name = ?")
            if (from != null) append(" AND m.time >= ?")
            if (to != null) append(" AND m.time <= ?")
            append(" ORDER BY m.time DESC LIMIT ?")
        }
        db.withConnection { conn ->
            conn.queryToMaps(sql) { ps ->
                var i = 1
                ps.setString(i++, hostname)
                if (interfaceName != null) ps.setString(i++, interfaceName)
                if (from != null) ps.setObject(i++, from)
                if (to != null) ps.setObject(i++, to)
                ps.setInt(i, limit)
            }
        }
    }

    fun linkEvents(
        hostname: String,
        interfaceName: String?,
        from: OffsetDateTime?,
        to: OffsetDateTime?,
        limit: Int,
    ): List<Map<String, Any?>> = safeQuery {
        val sql = buildString {
            append(
                """
                SELECT e.detected_at, i.name AS interface, e.event::text AS event,
                       e.prev_status::text AS prev_status, e.new_status::text AS new_status,
                       e.detail::text AS detail
                FROM link_events e
                JOIN interfaces i ON i.id = e.interface_id
                JOIN devices d ON d.id = i.device_id
                WHERE lower(d.hostname) = lower(?)
                """.trimIndent()
            )
            if (interfaceName != null) append(" AND i.name = ?")
            if (from != null) append(" AND e.detected_at >= ?")
            if (to != null) append(" AND e.detected_at <= ?")
            append(" ORDER BY e.detected_at DESC LIMIT ?")
        }
        db.withConnection { conn ->
            conn.queryToMaps(sql) { ps ->
                var i = 1
                ps.setString(i++, hostname)
                if (interfaceName != null) ps.setString(i++, interfaceName)
                if (from != null) ps.setObject(i++, from)
                if (to != null) ps.setObject(i++, to)
                ps.setInt(i, limit)
            }
        }
    }

    fun configDiffs(
        hostname: String,
        from: OffsetDateTime?,
        to: OffsetDateTime?,
        limit: Int,
    ): List<Map<String, Any?>> = safeQuery {
        val sql = buildString {
            append(
                """
                SELECT c.created_at, c.from_snapshot_id, c.to_snapshot_id,
                       c.lines_added, c.lines_removed, c.unified_diff, c.audit_id
                FROM config_diffs c
                JOIN devices d ON d.id = c.device_id
                WHERE lower(d.hostname) = lower(?)
                """.trimIndent()
            )
            if (from != null) append(" AND c.created_at >= ?")
            if (to != null) append(" AND c.created_at <= ?")
            append(" ORDER BY c.created_at DESC LIMIT ?")
        }
        db.withConnection { conn ->
            conn.queryToMaps(sql) { ps ->
                var i = 1
                ps.setString(i++, hostname)
                if (from != null) ps.setObject(i++, from)
                if (to != null) ps.setObject(i++, to)
                ps.setInt(i, limit)
            }
        }
    }

    fun commandAudit(
        hostname: String,
        from: OffsetDateTime?,
        to: OffsetDateTime?,
        limit: Int,
    ): List<Map<String, Any?>> = safeQuery {
        val sql = buildString {
            append(
                """
                SELECT a.occurred_at, a.session_uid, a.source, a.vendor::text AS vendor,
                       a.read_only, a.commands, a.rationale, a.risk_safe, a.risk_config,
                       a.risk_dangerous, a.decision::text AS decision, a.executed_count,
                       a.rejected_count, a.output_excerpt
                FROM command_audit a
                JOIN devices d ON d.id = a.device_id
                WHERE lower(d.hostname) = lower(?)
                """.trimIndent()
            )
            if (from != null) append(" AND a.occurred_at >= ?")
            if (to != null) append(" AND a.occurred_at <= ?")
            append(" ORDER BY a.occurred_at DESC LIMIT ?")
        }
        db.withConnection { conn ->
            conn.queryToMaps(sql) { ps ->
                var i = 1
                ps.setString(i++, hostname)
                if (from != null) ps.setObject(i++, from)
                if (to != null) ps.setObject(i++, to)
                ps.setInt(i, limit)
            }
        }
    }

    /** Vista `v_latest_interface_status` — última muestra por interfaz. */
    fun latestInterfaceStatus(hostname: String?): List<Map<String, Any?>> = safeQuery {
        db.withConnection { conn ->
            if (hostname == null) {
                conn.queryToMaps("SELECT * FROM v_latest_interface_status") { }
            } else {
                conn.queryToMaps("SELECT * FROM v_latest_interface_status WHERE lower(hostname) = lower(?)") { ps ->
                    ps.setString(1, hostname)
                }
            }
        }
    }

    private fun safeQuery(block: () -> List<Map<String, Any?>>): List<Map<String, Any?>> =
        runCatching(block).onFailure { log.warn("query de histórico falló: {}", it.message) }
            .getOrDefault(emptyList())
}
