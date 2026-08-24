package com.opentermx.telemetrydb

import com.opentermx.netparsers.InterfaceStats
import com.opentermx.netparsers.PortStatus
import com.opentermx.netparsers.Vendor
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/* =====================================================================================
 * Repositorios JDBC explícitos. Reglas del módulo:
 *  - PreparedStatement + bind params SIEMPRE (única excepción: DDL de particiones, ver
 *    MetricsRepository — el nombre sale de año/mes numéricos, jamás de input externo);
 *  - timestamps TIMESTAMPTZ en UTC (OffsetDateTime) — la conversión a zona local es
 *    problema de la UI (error #21);
 *  - nada lanza hacia el caller de telemetría: los errores se loguean y devuelven
 *    null/false para que la captura siga sin persistir.
 * ===================================================================================== */

/** `host` válido para la columna INET, o null (un hostname DNS no es un INET). */
fun inetOrNull(host: String?): String? {
    if (host.isNullOrBlank()) return null
    val v4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    return when {
        v4.matches(host) -> host
        host.contains(':') -> host // IPv6 literal — Postgres valida el formato exacto.
        else -> null
    }
}

class DeviceRepository internal constructor(private val db: TelemetryDb) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Alta/actualización por (mgmt_address, port). Devuelve el id, o null si [mgmtAddress]
     * no es una IP (la columna es INET; un DNS name no se inventa — se loguea y no se
     * persiste, degradación con gracia).
     */
    fun upsert(
        hostname: String,
        mgmtAddress: String?,
        port: Int,
        protocol: String,
        vendor: Vendor,
    ): Long? {
        val inet = inetOrNull(mgmtAddress)
        if (inet == null) {
            log.debug("Host `{}` no es una IP — no se persiste como device (columna INET)", mgmtAddress)
            return null
        }
        val proto = if (protocol.uppercase() in setOf("SSH", "TELNET", "SERIAL")) protocol.uppercase() else "SSH"
        return runCatching {
            db.withConnection { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO devices (hostname, mgmt_address, port, protocol, vendor)
                    VALUES (?, ?::inet, ?, ?::protocol_t, ?::vendor_t)
                    ON CONFLICT (mgmt_address, port) DO UPDATE
                      SET hostname = EXCLUDED.hostname,
                          vendor = EXCLUDED.vendor,
                          updated_at = now()
                    RETURNING id
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, hostname)
                    ps.setString(2, inet)
                    ps.setInt(3, port)
                    ps.setString(4, proto)
                    ps.setString(5, vendor.name)
                    ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                }
            }
        }.onFailure { log.warn("upsert device `{}` falló: {}", hostname, it.message) }.getOrNull()
    }

    /**
     * Alta/resolución para inventario: serie -> MAC base -> IP:puerto. La identidad
     * encontrada conserva el mismo id y registra cada combinación observada.
     */
    fun upsertInventory(
        hostname: String,
        mgmtAddress: String,
        port: Int,
        protocol: String,
        vendor: Vendor,
        model: String?,
        role: String?,
        credentialRef: String?,
        baseMac: String?,
        serialNumber: String?,
        source: String,
    ): Long? {
        val inet = inetOrNull(mgmtAddress) ?: return null
        val normalizedSerial = serialNumber?.trim()?.ifEmpty { null }
        val normalizedMac = normalizeMac(baseMac)
        val proto = if (protocol.uppercase() in setOf("SSH", "TELNET", "SERIAL")) protocol.uppercase() else "SSH"
        return runCatching {
            db.withConnection { conn ->
                conn.autoCommit = false
                try {
                    val existing = findStableId(conn, normalizedSerial, normalizedMac, inet, port)
                    val id = if (existing == null) {
                        conn.prepareStatement(
                            """
                            INSERT INTO devices (
                              hostname, mgmt_address, port, protocol, vendor, model, role,
                              credential_ref, base_mac, serial_number
                            ) VALUES (?, ?::inet, ?, ?::protocol_t, ?::vendor_t, ?, ?, ?, ?::macaddr, ?)
                            RETURNING id
                            """.trimIndent()
                        ).use { ps ->
                            bindInventory(ps, hostname, inet, port, proto, vendor, model, role,
                                credentialRef, normalizedMac, normalizedSerial)
                            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                        }
                    } else {
                        conn.prepareStatement(
                            """
                            UPDATE devices SET hostname=?, mgmt_address=?::inet, port=?, protocol=?::protocol_t,
                              vendor=?::vendor_t, model=COALESCE(?, model), role=COALESCE(?, role),
                              credential_ref=COALESCE(?, credential_ref), base_mac=COALESCE(?::macaddr, base_mac),
                              serial_number=COALESCE(?, serial_number), updated_at=now()
                            WHERE id=?
                            """.trimIndent()
                        ).use { ps ->
                            bindInventory(ps, hostname, inet, port, proto, vendor, model, role,
                                credentialRef, normalizedMac, normalizedSerial)
                            ps.setLong(11, existing)
                            ps.executeUpdate()
                        }
                        existing
                    }
                    conn.prepareStatement(
                        """
                        INSERT INTO device_identity_history
                          (device_id, hostname, mgmt_address, base_mac, serial_number, source)
                        VALUES (?, ?, ?::inet, ?::macaddr, ?, ?)
                        ON CONFLICT DO NOTHING
                        """.trimIndent()
                    ).use { ps ->
                        ps.setLong(1, id); ps.setString(2, hostname); ps.setString(3, inet)
                        ps.setString(4, normalizedMac); ps.setString(5, normalizedSerial); ps.setString(6, source)
                        ps.executeUpdate()
                    }
                    conn.commit()
                    id
                } catch (e: Throwable) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.autoCommit = true
                }
            }
        }.onFailure { log.warn("upsertInventory `{}` falló: {}", hostname, it.message) }.getOrNull()
    }

    private fun findStableId(
        conn: Connection,
        serialNumber: String?,
        baseMac: String?,
        inet: String,
        port: Int,
    ): Long? = conn.prepareStatement(
        """
        SELECT id FROM devices
        WHERE (? IS NOT NULL AND lower(serial_number) = lower(?))
           OR (?::macaddr IS NOT NULL AND base_mac = ?::macaddr)
           OR (mgmt_address = ?::inet AND port = ?)
        ORDER BY CASE
          WHEN ? IS NOT NULL AND lower(serial_number) = lower(?) THEN 1
          WHEN ?::macaddr IS NOT NULL AND base_mac = ?::macaddr THEN 2 ELSE 3 END
        LIMIT 1
        """.trimIndent()
    ).use { ps ->
        ps.setString(1, serialNumber); ps.setString(2, serialNumber)
        ps.setString(3, baseMac); ps.setString(4, baseMac)
        ps.setString(5, inet); ps.setInt(6, port)
        ps.setString(7, serialNumber); ps.setString(8, serialNumber)
        ps.setString(9, baseMac); ps.setString(10, baseMac)
        ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
    }

    private fun bindInventory(
        ps: java.sql.PreparedStatement,
        hostname: String,
        inet: String,
        port: Int,
        protocol: String,
        vendor: Vendor,
        model: String?,
        role: String?,
        credentialRef: String?,
        baseMac: String?,
        serialNumber: String?,
    ) {
        ps.setString(1, hostname); ps.setString(2, inet); ps.setInt(3, port)
        ps.setString(4, protocol); ps.setString(5, vendor.name); ps.setString(6, model)
        ps.setString(7, role); ps.setString(8, credentialRef); ps.setString(9, baseMac)
        ps.setString(10, serialNumber)
    }

    private fun normalizeMac(value: String?): String? {
        val hex = value?.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            ?.lowercase()?.ifEmpty { null } ?: return null
        require(hex.length == 12) { "MAC inválida" }
        return hex.chunked(2).joinToString(":")
    }

    fun findIdByHostname(hostname: String): Long? = runCatching {
        db.withConnection { conn ->
            conn.prepareStatement("SELECT id FROM devices WHERE lower(hostname) = lower(?) LIMIT 1").use { ps ->
                ps.setString(1, hostname)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
            }
        }
    }.getOrNull()

    /**
     * Borra el device del inventario (Fase 5, error #49). El CASCADE del esquema se
     * lleva interfaces, métricas, fingerprints, perfil, vecinos y snapshots; la
     * auditoría de comandos SOBREVIVE con `device_id = NULL` (ON DELETE SET NULL) —
     * borrar un equipo no borra el registro de lo que se le hizo. El doc RAG lo
     * limpia el hook del caller ([com.opentermx.fingerprint] removeFor) o, como red
     * de seguridad, la pasada de huérfanos de regenerateAll.
     */
    fun delete(id: Long): Boolean = runCatching {
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM devices WHERE id = ?").use { ps ->
                ps.setLong(1, id)
                ps.executeUpdate() > 0
            }
        }
    }.onFailure { log.warn("delete device {} falló: {}", id, it.message) }.getOrDefault(false)

    /** Lookup por IP de management (las sesiones identifican al equipo por host, no hostname). */
    fun findIdByMgmtAddress(address: String?): Long? {
        val inet = inetOrNull(address) ?: return null
        return runCatching {
            db.withConnection { conn ->
                conn.prepareStatement("SELECT id FROM devices WHERE mgmt_address = ?::inet LIMIT 1").use { ps ->
                    ps.setString(1, inet)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                }
            }
        }.getOrNull()
    }

    /** Setea el sitio del device (campo del operador; onboarding 6B). */
    fun updateSite(id: Long, site: String?): Boolean = runCatching {
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE devices SET site = ?, updated_at = now() WHERE id = ?").use { ps ->
                ps.setString(1, site?.trim()?.ifEmpty { null })
                ps.setLong(2, id)
                ps.executeUpdate() > 0
            }
        }
    }.onFailure { log.warn("updateSite device={} falló: {}", id, it.message) }.getOrDefault(false)

    /** Fila completa del device (Fase 5C), o null. */
    fun findById(id: Long): Map<String, Any?>? = runCatching {
        db.withConnection { conn ->
            conn.queryToMaps(
                "SELECT id, hostname, mgmt_address::text AS mgmt_address, port, protocol::text AS protocol, " +
                    "vendor::text AS vendor, model, os_version, serial_number, site, role, enabled, " +
                    "created_at, updated_at FROM devices WHERE id = ?"
            ) { it.setLong(1, id) }.firstOrNull()
        }
    }.getOrNull()

    /**
     * Inventario con filtros opcionales (tool `list_devices`, Fase 5C). Los filtros de
     * texto comparan case-insensitive exacto; `criticality` viene de device_profiles
     * (LEFT JOIN: device sin perfil cuenta como 'medium' default).
     */
    fun list(
        role: String? = null,
        site: String? = null,
        vendor: String? = null,
        criticality: String? = null,
        limit: Int = 100,
    ): List<Map<String, Any?>> = runCatching {
        val clauses = mutableListOf<String>()
        val binds = mutableListOf<String>()
        if (!role.isNullOrBlank()) { clauses += "lower(d.role) = lower(?)"; binds += role }
        if (!site.isNullOrBlank()) { clauses += "lower(d.site) = lower(?)"; binds += site }
        if (!vendor.isNullOrBlank()) { clauses += "lower(d.vendor::text) = lower(?)"; binds += vendor }
        if (!criticality.isNullOrBlank()) {
            clauses += "lower(COALESCE(p.criticality, 'medium')) = lower(?)"
            binds += criticality
        }
        val where = if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")
        db.withConnection { conn ->
            conn.queryToMaps(
                """
                SELECT d.id, d.hostname, d.mgmt_address::text AS mgmt_address, d.port,
                       d.vendor::text AS vendor, d.model, d.os_version, d.site, d.role,
                       COALESCE(p.criticality, 'medium') AS criticality,
                       p.role_source::text AS role_source, d.enabled
                FROM devices d
                LEFT JOIN device_profiles p ON p.device_id = d.id
                $where
                ORDER BY lower(d.hostname)
                LIMIT ?
                """.trimIndent()
            ) { ps ->
                binds.forEachIndexed { i, value -> ps.setString(i + 1, value) }
                ps.setInt(binds.size + 1, limit.coerceIn(1, 500))
            }
        }
    }.onFailure { log.warn("list devices falló: {}", it.message) }.getOrDefault(emptyList())
}

class InterfaceRepository internal constructor(private val db: TelemetryDb) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun upsert(deviceId: Long, name: String, description: String?, speedBps: Long?): Long? = runCatching {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO interfaces (device_id, name, description, speed_bps)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (device_id, name) DO UPDATE
                  SET last_seen = now(),
                      description = COALESCE(EXCLUDED.description, interfaces.description),
                      speed_bps = COALESCE(EXCLUDED.speed_bps, interfaces.speed_bps)
                RETURNING id
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, deviceId)
                ps.setString(2, name)
                ps.setString(3, description)
                if (speedBps != null) ps.setLong(4, speedBps) else ps.setNull(4, java.sql.Types.BIGINT)
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
        }
    }.onFailure { log.warn("upsert interface `{}` falló: {}", name, it.message) }.getOrNull()
}

class MetricsRepository internal constructor(private val db: TelemetryDb) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val ensuredMonths = ConcurrentHashMap.newKeySet<String>()

    /**
     * Inserta una muestra. Si la partición del mes no existe (`no partition of relation`,
     * error #18 del catálogo) la crea on-the-fly y reintenta UNA vez.
     */
    fun insert(
        interfaceId: Long,
        time: OffsetDateTime,
        stats: InterfaceStats,
        utilizationInPct: Double? = null,
        utilizationOutPct: Double? = null,
        collectionMethod: String = "ssh_parse",
    ): Boolean {
        val utc = time.withOffsetSameInstant(ZoneOffset.UTC)
        ensurePartition(YearMonth.from(utc))
        return runCatching {
            try {
                doInsert(interfaceId, utc, stats, utilizationInPct, utilizationOutPct, collectionMethod)
            } catch (e: SQLException) {
                if (e.message?.contains("no partition of relation", ignoreCase = true) == true) {
                    ensuredMonths.clear() // el cache mintió (p. ej. partición dropeada): recrear.
                    ensurePartition(YearMonth.from(utc))
                    doInsert(interfaceId, utc, stats, utilizationInPct, utilizationOutPct, collectionMethod)
                } else throw e
            }
            true
        }.onFailure { log.warn("insert metric iface={} falló: {}", interfaceId, it.message) }
            .getOrDefault(false)
    }

    private fun doInsert(
        interfaceId: Long,
        time: OffsetDateTime,
        stats: InterfaceStats,
        utilIn: Double?,
        utilOut: Double?,
        method: String,
    ) {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO interface_metrics (
                  time, interface_id, oper_status, input_rate_bps, output_rate_bps,
                  input_packets, output_packets, input_errors, output_errors, crc_errors,
                  input_drops, output_drops, utilization_in_pct, utilization_out_pct, collection_method
                ) VALUES (?, ?, ?::port_status_t, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setObject(1, time)
                ps.setLong(2, interfaceId)
                ps.setString(3, stats.operStatus.name)
                ps.setNullableLong(4, stats.inputRateBps)
                ps.setNullableLong(5, stats.outputRateBps)
                ps.setNullableLong(6, stats.inputPackets)
                ps.setNullableLong(7, stats.outputPackets)
                ps.setNullableLong(8, stats.inputErrors)
                ps.setNullableLong(9, stats.outputErrors)
                ps.setNullableLong(10, stats.crcErrors)
                ps.setNullableLong(11, stats.inputDrops)
                ps.setNullableLong(12, stats.outputDrops)
                ps.setNullableFloat(13, utilIn?.toFloat())
                ps.setNullableFloat(14, utilOut?.toFloat())
                ps.setString(15, method)
                ps.executeUpdate()
            }
        }
    }

    /**
     * `CREATE TABLE IF NOT EXISTS interface_metrics_YYYY_MM PARTITION OF ...`.
     * ÚNICO punto del módulo con SQL construido por interpolación: el nombre sale de
     * [YearMonth] (dos enteros validados por java.time), nunca de input externo.
     */
    fun ensurePartition(month: YearMonth) {
        val key = "%04d_%02d".format(month.year, month.monthValue)
        if (!ensuredMonths.add(key)) return
        val from = month.atDay(1)
        val to = month.plusMonths(1).atDay(1)
        runCatching {
            db.withConnection { conn ->
                conn.createStatement().use { st ->
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS interface_metrics_$key " +
                            "PARTITION OF interface_metrics FOR VALUES FROM ('$from') TO ('$to')"
                    )
                }
            }
        }.onFailure {
            ensuredMonths.remove(key)
            log.warn("ensurePartition {} falló: {}", key, it.message)
        }
    }

    /** Último oper_status conocido de la interfaz — para detectar transiciones de enlace. */
    fun lastOperStatus(interfaceId: Long): PortStatus? = runCatching {
        db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT oper_status FROM interface_metrics WHERE interface_id = ? ORDER BY time DESC LIMIT 1"
            ).use { ps ->
                ps.setLong(1, interfaceId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) PortStatus.valueOf(rs.getString(1)) else null
                }
            }
        }
    }.getOrNull()
}

class LinkEventRepository internal constructor(private val db: TelemetryDb) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun insert(
        interfaceId: Long,
        event: String,      // LINK_UP, LINK_DOWN, FLAPPING, ERR_DISABLED, SPEED_CHANGE
        prevStatus: PortStatus?,
        newStatus: PortStatus?,
        detailJson: String = "{}",
    ): Boolean = runCatching {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO link_events (interface_id, event, prev_status, new_status, detail)
                VALUES (?, ?::link_event_t, ?::port_status_t, ?::port_status_t, ?::jsonb)
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, interfaceId)
                ps.setString(2, event)
                ps.setString(3, prevStatus?.name)
                ps.setString(4, newStatus?.name)
                ps.setString(5, detailJson)
                ps.executeUpdate()
            }
        }
        true
    }.onFailure { log.warn("insert link_event falló: {}", it.message) }.getOrDefault(false)
}

class AuditRepository internal constructor(private val db: TelemetryDb) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Inserta una fila de auditoría. Con [legacyRowHash] no-null el insert es idempotente
     * (`ON CONFLICT (legacy_row_hash) DO NOTHING`) — devuelve false si ya existía.
     */
    fun insert(
        occurredAt: OffsetDateTime,
        sessionUid: String,
        deviceId: Long?,
        source: String,
        vendor: Vendor,
        readOnly: Boolean,
        commands: List<String>,
        rationale: String?,
        riskSafe: Int,
        riskConfig: Int,
        riskDangerous: Int,
        decision: String,   // APPROVED, REJECTED, PARTIAL, AUTO_READONLY
        executedCount: Int,
        rejectedCount: Int,
        outputExcerpt: String?,
        operator: String? = null,
        legacyRowHash: String? = null,
    ): Boolean = runCatching {
        val excerpt = outputExcerpt?.let {
            if (it.length > MAX_EXCERPT_CHARS) it.take(MAX_EXCERPT_CHARS) + "…[truncated]" else it
        }
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO command_audit (
                  occurred_at, session_uid, device_id, source, vendor, read_only, commands,
                  rationale, risk_safe, risk_config, risk_dangerous, decision,
                  executed_count, rejected_count, output_excerpt, operator, legacy_row_hash
                ) VALUES (?, ?, ?, ?, ?::vendor_t, ?, ?, ?, ?, ?, ?, ?::decision_t, ?, ?, ?, ?, ?)
                ON CONFLICT (legacy_row_hash) DO NOTHING
                """.trimIndent()
            ).use { ps ->
                ps.setObject(1, occurredAt.withOffsetSameInstant(ZoneOffset.UTC))
                ps.setString(2, sessionUid)
                if (deviceId != null) ps.setLong(3, deviceId) else ps.setNull(3, java.sql.Types.BIGINT)
                ps.setString(4, source)
                ps.setString(5, vendor.name)
                ps.setBoolean(6, readOnly)
                ps.setArray(7, conn.createArrayOf("text", commands.toTypedArray()))
                ps.setString(8, rationale)
                ps.setInt(9, riskSafe)
                ps.setInt(10, riskConfig)
                ps.setInt(11, riskDangerous)
                ps.setString(12, decision)
                ps.setInt(13, executedCount)
                ps.setInt(14, rejectedCount)
                ps.setString(15, excerpt)
                ps.setString(16, operator)
                ps.setString(17, legacyRowHash)
                ps.executeUpdate() > 0
            }
        }
    }.onFailure { log.warn("insert command_audit falló: {}", it.message) }.getOrDefault(false)

    companion object {
        const val MAX_EXCERPT_CHARS = 8 * 1024
    }
}

class DeviceActivityRepository internal constructor(private val db: TelemetryDb) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun append(
        deviceId: Long?,
        deviceName: String,
        mgmtAddress: String?,
        actor: String,
        activityType: String,
        summary: String,
        outcome: String,
        source: String,
        correlationId: String? = null,
        detailsJson: String = "{}",
        occurredAt: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC),
    ): Long? {
        require(deviceName.isNotBlank()) { "deviceName no puede estar vacío" }
        require(actor.isNotBlank()) { "actor no puede estar vacío" }
        require(activityType.isNotBlank()) { "activityType no puede estar vacío" }
        require(summary.isNotBlank()) { "summary no puede estar vacío" }
        require(outcome.isNotBlank()) { "outcome no puede estar vacío" }
        require(source.isNotBlank()) { "source no puede estar vacío" }
        val inet = inetOrNull(mgmtAddress)
        return runCatching {
            db.withConnection { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO device_activity (
                      occurred_at, device_id, device_name, mgmt_address, actor,
                      activity_type, summary, outcome, source, correlation_id, details
                    ) VALUES (?, ?, ?, ?::inet, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    RETURNING id
                    """.trimIndent()
                ).use { ps ->
                    ps.setObject(1, occurredAt.withOffsetSameInstant(ZoneOffset.UTC))
                    if (deviceId != null) ps.setLong(2, deviceId) else ps.setNull(2, java.sql.Types.BIGINT)
                    ps.setString(3, deviceName)
                    ps.setString(4, inet)
                    ps.setString(5, actor)
                    ps.setString(6, activityType)
                    ps.setString(7, summary)
                    ps.setString(8, outcome)
                    ps.setString(9, source)
                    ps.setString(10, correlationId)
                    ps.setString(11, detailsJson)
                    ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                }
            }
        }.onFailure { log.warn("append device_activity falló: {}", it.message) }.getOrNull()
    }
}

class SnapshotRepository internal constructor(private val db: TelemetryDb) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Sanitiza, hashea (sobre el texto YA sanitizado — la dedup no debe depender de
     * secretos) y persiste. Devuelve el id del snapshot o null.
     */
    fun insert(deviceId: Long, trigger: String, rawConfigText: String, auditId: Long? = null): Long? {
        val sanitized = ConfigSanitizer.sanitize(rawConfigText)
        val sha = ConfigSanitizer.sha256(sanitized)
        return runCatching {
            db.withConnection { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO config_snapshots (device_id, "trigger", audit_id, config_sha256, config_text, line_count)
                    VALUES (?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """.trimIndent()
                ).use { ps ->
                    ps.setLong(1, deviceId)
                    ps.setString(2, trigger)
                    if (auditId != null) ps.setLong(3, auditId) else ps.setNull(3, java.sql.Types.BIGINT)
                    ps.setString(4, sha)
                    ps.setString(5, sanitized)
                    ps.setInt(6, sanitized.lineSequence().count())
                    ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                }
            }
        }.onFailure { log.warn("insert snapshot falló: {}", it.message) }.getOrNull()
    }

    fun insertDiff(
        deviceId: Long,
        fromSnapshotId: Long,
        toSnapshotId: Long,
        unifiedDiff: String,
        linesAdded: Int,
        linesRemoved: Int,
        auditId: Long? = null,
    ): Long? = runCatching {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO config_diffs (device_id, from_snapshot_id, to_snapshot_id, unified_diff, lines_added, lines_removed, audit_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, deviceId)
                ps.setLong(2, fromSnapshotId)
                ps.setLong(3, toSnapshotId)
                ps.setString(4, unifiedDiff)
                ps.setInt(5, linesAdded)
                ps.setInt(6, linesRemoved)
                if (auditId != null) ps.setLong(7, auditId) else ps.setNull(7, java.sql.Types.BIGINT)
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
        }
    }.onFailure { log.warn("insert config_diff falló: {}", it.message) }.getOrNull()
}

// --------------------------------------------------------------------- helpers JDBC

internal fun java.sql.PreparedStatement.setNullableLong(idx: Int, value: Long?) {
    if (value != null) setLong(idx, value) else setNull(idx, java.sql.Types.BIGINT)
}

internal fun java.sql.PreparedStatement.setNullableFloat(idx: Int, value: Float?) {
    if (value != null) setFloat(idx, value) else setNull(idx, java.sql.Types.REAL)
}

internal fun ResultSet.rowToMap(): Map<String, Any?> {
    val meta = metaData
    return (1..meta.columnCount).associate { i ->
        val raw = getObject(i)
        meta.getColumnLabel(i) to when (raw) {
            is java.sql.Timestamp -> raw.toInstant().toString()
            is java.sql.Array -> (raw.array as? Array<*>)?.toList()
            is OffsetDateTime -> raw.toInstant().toString()
            else -> raw
        }
    }
}

internal fun Connection.queryToMaps(sql: String, binder: (java.sql.PreparedStatement) -> Unit): List<Map<String, Any?>> =
    prepareStatement(sql).use { ps ->
        binder(ps)
        ps.executeQuery().use { rs ->
            val out = mutableListOf<Map<String, Any?>>()
            while (rs.next()) out += rs.rowToMap()
            out
        }
    }
