package com.opentermx.telemetrydb

import com.opentermx.fingerprint.DeviceIdentity
import com.opentermx.fingerprint.neighbors.NeighborEntry
import java.sql.Connection
import org.slf4j.LoggerFactory

/* =====================================================================================
 * Persistencia de identidad y perfiles de dispositivos (Fase 5B). Mismas reglas que
 * Repositories.kt: PreparedStatement siempre, errores logueados sin lanzar al camino
 * de telemetría, timestamps UTC.
 *
 * Regla de MERGE por propietario de campo (error #39):
 *   - el SISTEMA escribe identidad (vendor/model/os_version/serial) y vecinos;
 *   - el HUMANO escribe contexto operativo (role confirmado, criticality, notes,
 *     forbidden, maintenanceWindow, contact) y el sistema JAMÁS lo pisa.
 * ===================================================================================== */

/** Transacción explícita sobre una conexión del pool. */
internal fun <T> TelemetryDb.inTransaction(block: (Connection) -> T): T =
    withConnection { conn ->
        val previous = conn.autoCommit
        conn.autoCommit = false
        try {
            val result = block(conn)
            conn.commit()
            result
        } catch (e: Throwable) {
            runCatching { conn.rollback() }
            throw e
        } finally {
            conn.autoCommit = previous
        }
    }

class FingerprintRepository internal constructor(private val db: TelemetryDb) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Último fingerprint registrado del dispositivo, o null. */
    fun latest(deviceId: Long): Map<String, Any?>? = runCatching {
        db.withConnection { conn ->
            conn.queryToMaps(
                "SELECT * FROM device_fingerprints WHERE device_id = ? ORDER BY taken_at DESC LIMIT 1"
            ) { it.setLong(1, deviceId) }.firstOrNull()
        }
    }.getOrNull()

    fun listRecent(deviceId: Long, limit: Int = 5): List<Map<String, Any?>> = runCatching {
        db.withConnection { conn ->
            conn.queryToMaps(
                "SELECT * FROM device_fingerprints WHERE device_id = ? ORDER BY taken_at DESC LIMIT ?"
            ) { ps -> ps.setLong(1, deviceId); ps.setInt(2, limit.coerceIn(1, 100)) }
        }
    }.getOrDefault(emptyList())

    /**
     * Retención del histórico (error #43): conserva los últimos [keepPerDevice] por
     * dispositivo. Lo llama el job diario de mantenimiento. Devuelve filas borradas.
     */
    fun pruneKeepingLast(keepPerDevice: Int = DEFAULT_KEEP_PER_DEVICE): Int = runCatching {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                DELETE FROM device_fingerprints WHERE id IN (
                  SELECT id FROM (
                    SELECT id, row_number() OVER (PARTITION BY device_id ORDER BY taken_at DESC) AS rn
                    FROM device_fingerprints
                  ) ranked WHERE rn > ?
                )
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, keepPerDevice.coerceAtLeast(1))
                ps.executeUpdate()
            }
        }
    }.onFailure { log.warn("prune de device_fingerprints falló: {}", it.message) }
        .getOrDefault(0).also { if (it > 0) log.info("device_fingerprints: {} filas viejas borradas", it) }

    companion object {
        const val DEFAULT_KEEP_PER_DEVICE = 20
    }
}

class ProfileRepository internal constructor(private val db: TelemetryDb) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Perfil vigente + procedencia. [profile] ya pasó por [ProfileMigrator]. */
    data class Record(
        val deviceId: Long,
        val schemaVersion: Int,
        val roleSource: String,
        val criticality: String,
        val role: String?,
        val profile: Map<String, Any?>,
        val updatedAt: String?,
        val updatedBy: String,
        val lastFingerprintAt: String?,
        val lastConfidence: String?,
    )

    /** `sealed`: el compilador obliga a manejar Missing — prohibido null con significado. */
    sealed interface LoadResult {
        data class Loaded(val record: Record, val warnings: List<String>) : LoadResult
        data object Missing : LoadResult
    }

    fun load(deviceId: Long): LoadResult = runCatching {
        val row = db.withConnection { conn ->
            conn.queryToMaps(
                """
                SELECT p.device_id, p.schema_version, p.role_source, p.criticality,
                       p.profile::text AS profile_json, p.updated_at, p.updated_by,
                       d.role,
                       f.taken_at AS last_fingerprint_at, f.confidence AS last_confidence
                FROM device_profiles p
                JOIN devices d ON d.id = p.device_id
                LEFT JOIN LATERAL (
                  SELECT taken_at, confidence FROM device_fingerprints
                  WHERE device_id = p.device_id ORDER BY taken_at DESC LIMIT 1
                ) f ON TRUE
                WHERE p.device_id = ?
                """.trimIndent()
            ) { it.setLong(1, deviceId) }.firstOrNull()
        } ?: return LoadResult.Missing

        val migrated = ProfileMigrator.migrate(
            row["profile_json"] as? String,
            (row["schema_version"] as? Number)?.toInt() ?: ProfileMigrator.CURRENT_VERSION,
        )
        migrated.warnings.forEach { log.warn("profile.migrated device={} — {}", deviceId, it) }
        LoadResult.Loaded(
            Record(
                deviceId = deviceId,
                schemaVersion = ProfileMigrator.CURRENT_VERSION,
                roleSource = row["role_source"].toString(),
                criticality = row["criticality"].toString(),
                role = row["role"] as? String,
                profile = migrated.profile,
                updatedAt = row["updated_at"] as? String,
                updatedBy = row["updated_by"].toString(),
                lastFingerprintAt = row["last_fingerprint_at"] as? String,
                lastConfidence = row["last_confidence"]?.toString(),
            ),
            migrated.warnings,
        )
    }.getOrElse { e ->
        // Hasta un fallo de SQL degrada a Missing con warning: load() jamás lanza.
        log.warn("load profile device={} falló: {}", deviceId, e.message)
        LoadResult.Missing
    }

    data class ApplyResult(
        val fingerprintId: Long?,
        val identityChanged: Boolean,
        val changes: List<String>,
    )

    /**
     * Aplica un fingerprint nuevo en UNA transacción: guarda la fila histórica en
     * `device_fingerprints`, actualiza la identidad system-owned de `devices` y asegura
     * la fila de `device_profiles`. Si la identidad difiere de la anterior emite WARN
     * `device.identity.changed` (regla 5B-1: un cambio de modelo con la misma IP puede
     * ser un reemplazo de hardware o algo peor).
     *
     * Campos del operador: intactos. El rol sugerido solo se escribe si `role_source`
     * no es OPERATOR y la sugerencia no es el fallback.
     */
    fun applyFingerprint(
        deviceId: Long,
        identity: DeviceIdentity,
        roleSuggestion: String? = null,
        probeId: String,
        traceId: String? = null,
        rawExcerpt: String? = null,
    ): ApplyResult? = runCatching {
        db.inTransaction { conn ->
            val before = conn.queryToMaps(
                "SELECT model, os_version, vendor::text AS vendor, role FROM devices WHERE id = ? FOR UPDATE"
            ) { it.setLong(1, deviceId) }.firstOrNull()
                ?: throw IllegalArgumentException("device $deviceId inexistente")

            val changes = mutableListOf<String>()
            val prevModel = before["model"] as? String
            val prevOs = before["os_version"] as? String
            if (identity.model != null && prevModel != null && identity.model != prevModel) {
                changes += "model: `$prevModel` -> `${identity.model}`"
            }
            if (identity.osVersion != null && prevOs != null && identity.osVersion != prevOs) {
                changes += "os_version: `$prevOs` -> `${identity.osVersion}`"
            }

            val fingerprintId = insertFingerprint(conn, deviceId, identity, probeId, traceId, rawExcerpt)

            // Identidad system-owned: COALESCE para no blanquear con un fingerprint pobre.
            conn.prepareStatement(
                """
                UPDATE devices SET
                  model = COALESCE(?, model),
                  os_version = COALESCE(?, os_version),
                  serial_number = COALESCE(?, serial_number),
                  vendor = CASE WHEN ?::vendor_t <> 'UNKNOWN' THEN ?::vendor_t ELSE vendor END,
                  updated_at = now()
                WHERE id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, identity.model)
                ps.setString(2, identity.osVersion)
                ps.setString(3, identity.serialNumbers.firstOrNull())
                ps.setString(4, identity.vendor.name)
                ps.setString(5, identity.vendor.name)
                ps.setLong(6, deviceId)
                ps.executeUpdate()
            }

            conn.prepareStatement(
                "INSERT INTO device_profiles (device_id) VALUES (?) ON CONFLICT (device_id) DO NOTHING"
            ).use { ps -> ps.setLong(1, deviceId); ps.executeUpdate() }

            if (!roleSuggestion.isNullOrBlank() && roleSuggestion != "unknown") {
                conn.prepareStatement(
                    """
                    UPDATE devices d SET role = ?, updated_at = now()
                    FROM device_profiles p
                    WHERE d.id = ? AND p.device_id = d.id AND p.role_source <> 'OPERATOR'
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, roleSuggestion)
                    ps.setLong(2, deviceId)
                    ps.executeUpdate()
                }
            }

            identity.extras["stackMembers"]?.let { members ->
                conn.prepareStatement(
                    """
                    UPDATE device_profiles SET profile = jsonb_set(
                      jsonb_set(profile, '{custom}', COALESCE(profile->'custom', '{}'::jsonb), true),
                      '{custom,stackMembers}', to_jsonb(?::text), true
                    ) WHERE device_id = ?
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, members)
                    ps.setLong(2, deviceId)
                    ps.executeUpdate()
                }
            }

            if (changes.isNotEmpty()) {
                log.warn(
                    "device.identity.changed device={} traceId={} — {}",
                    deviceId, traceId, changes.joinToString("; "),
                )
            }
            ApplyResult(fingerprintId, changes.isNotEmpty(), changes)
        }
    }.onFailure { log.warn("applyFingerprint device={} falló: {}", deviceId, it.message) }.getOrNull()

    private fun insertFingerprint(
        conn: Connection,
        deviceId: Long,
        identity: DeviceIdentity,
        probeId: String,
        traceId: String?,
        rawExcerpt: String?,
    ): Long {
        conn.prepareStatement(
            """
            INSERT INTO device_fingerprints
              (device_id, probe_id, vendor, model, os_version, serials, hostname, ha_role, confidence, trace_id, raw_excerpt)
            VALUES (?, ?, ?::vendor_t, ?, ?, ?, ?, ?, ?::confidence_t, ?, ?)
            RETURNING id
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, deviceId)
            ps.setString(2, probeId)
            ps.setString(3, identity.vendor.name)
            ps.setString(4, identity.model)
            ps.setString(5, identity.osVersion)
            ps.setArray(6, conn.createArrayOf("text", identity.serialNumbers.toTypedArray()))
            ps.setString(7, identity.hostname)
            ps.setString(8, identity.haRole)
            ps.setString(9, identity.confidence.name)
            ps.setString(10, traceId)
            ps.setString(11, rawExcerpt?.take(MAX_RAW_EXCERPT_CHARS))
            ps.executeQuery().use { rs -> rs.next(); return rs.getLong(1) }
        }
    }

    /**
     * Campos del OPERADOR (regla 5B-2). Confirmar un rol lo marca `role_source=OPERATOR`
     * y desde entonces ningún fingerprint lo pisa. Solo toca lo que viene no-null.
     */
    fun updateOperatorFields(
        deviceId: Long,
        role: String? = null,
        criticality: String? = null,
        notes: String? = null,
        forbidden: List<String>? = null,
        maintenanceWindow: String? = null,
        contact: String? = null,
        updatedBy: String = "operator",
    ): Boolean = runCatching {
        require(criticality == null || criticality in CRITICALITY_VALUES) {
            "criticality `$criticality` inválida (${CRITICALITY_VALUES.joinToString("|")})"
        }
        db.inTransaction { conn ->
            conn.prepareStatement(
                "INSERT INTO device_profiles (device_id) VALUES (?) ON CONFLICT (device_id) DO NOTHING"
            ).use { ps -> ps.setLong(1, deviceId); ps.executeUpdate() }

            if (role != null) {
                conn.prepareStatement("UPDATE devices SET role = ?, updated_at = now() WHERE id = ?").use { ps ->
                    ps.setString(1, role)
                    ps.setLong(2, deviceId)
                    ps.executeUpdate()
                }
                conn.prepareStatement(
                    "UPDATE device_profiles SET role_source = 'OPERATOR', updated_by = ? WHERE device_id = ?"
                ).use { ps -> ps.setString(1, updatedBy); ps.setLong(2, deviceId); ps.executeUpdate() }
            }
            if (criticality != null) {
                conn.prepareStatement(
                    "UPDATE device_profiles SET criticality = ?, updated_by = ? WHERE device_id = ?"
                ).use { ps ->
                    ps.setString(1, criticality)
                    ps.setString(2, updatedBy)
                    ps.setLong(3, deviceId)
                    ps.executeUpdate()
                }
            }
            setJsonField(conn, deviceId, "{notes}", notes?.let { ProfileMigrator.toJson(it) }, updatedBy)
            setJsonField(conn, deviceId, "{maintenanceWindow}", maintenanceWindow?.let { ProfileMigrator.toJson(it) }, updatedBy)
            setJsonField(conn, deviceId, "{contact}", contact?.let { ProfileMigrator.toJson(it) }, updatedBy)
            if (forbidden != null) {
                conn.prepareStatement(
                    """
                    UPDATE device_profiles SET profile = jsonb_set(
                      jsonb_set(profile, '{capabilities}', COALESCE(profile->'capabilities', '{}'::jsonb), true),
                      '{capabilities,forbidden}', ?::jsonb, true
                    ), updated_by = ? WHERE device_id = ?
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, ProfileMigrator.toJson(forbidden))
                    ps.setString(2, updatedBy)
                    ps.setLong(3, deviceId)
                    ps.executeUpdate()
                }
            }
            true
        }
    }.onFailure { log.warn("updateOperatorFields device={} falló: {}", deviceId, it.message) }
        .getOrDefault(false)

    /**
     * Devuelve el rol al control del SISTEMA (`role_source=INFERRED`): el próximo
     * fingerprint vuelve a escribir la sugerencia. Es el inverso de confirmar el rol
     * con [updateOperatorFields] — la UI de perfiles lo usa al destildar "confirmar".
     */
    fun releaseRoleToInferred(deviceId: Long, updatedBy: String = "operator"): Boolean = runCatching {
        db.withConnection { conn ->
            conn.prepareStatement(
                "UPDATE device_profiles SET role_source = 'INFERRED', updated_by = ? WHERE device_id = ?"
            ).use { ps ->
                ps.setString(1, updatedBy)
                ps.setLong(2, deviceId)
                ps.executeUpdate() > 0
            }
        }
    }.onFailure { log.warn("releaseRoleToInferred device={} falló: {}", deviceId, it.message) }
        .getOrDefault(false)

    private fun setJsonField(conn: Connection, deviceId: Long, path: String, jsonValue: String?, updatedBy: String) {
        if (jsonValue == null) return
        conn.prepareStatement(
            "UPDATE device_profiles SET profile = jsonb_set(profile, ?::text[], ?::jsonb, true), updated_by = ? WHERE device_id = ?"
        ).use { ps ->
            ps.setString(1, path)
            ps.setString(2, jsonValue)
            ps.setString(3, updatedBy)
            ps.setLong(4, deviceId)
            ps.executeUpdate()
        }
    }

    companion object {
        const val MAX_RAW_EXCERPT_CHARS = 2048
        val CRITICALITY_VALUES = setOf("low", "medium", "high", "critical")
    }
}

class NeighborRepository internal constructor(private val db: TelemetryDb) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Reemplazo TRANSACCIONAL del bloque de vecinos del dispositivo (error #41):
     * `DELETE WHERE device_id` + insert batch en la misma transacción — un fallo a
     * mitad hace rollback completo, jamás mezcla descubrimientos viejos y nuevos.
     *
     * Match contra inventario (error #42): `remote_hostname` -> `devices.hostname`
     * exige UNICIDAD (exacto, o sin dominio si el exacto no resuelve); con 2+
     * candidatos queda NULL y se loguea — nunca se adivina topología.
     */
    fun replaceAll(deviceId: Long, neighbors: List<NeighborEntry>): Boolean = runCatching {
        // Dedup por la clave única de la tabla (un parser puede repetir filas).
        val unique = neighbors.distinctBy {
            Triple(it.localInterface, it.remoteHostname, it.protocol)
        }
        db.inTransaction { conn ->
            conn.prepareStatement("DELETE FROM device_neighbors WHERE device_id = ?").use { ps ->
                ps.setLong(1, deviceId)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                """
                INSERT INTO device_neighbors
                  (device_id, local_interface, remote_hostname, remote_port, remote_device_id, protocol)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                for (n in unique) {
                    ps.setLong(1, deviceId)
                    ps.setString(2, n.localInterface)
                    ps.setString(3, n.remoteHostname)
                    ps.setString(4, n.remotePort)
                    val match = matchDeviceId(conn, n.remoteHostname)
                    if (match != null) ps.setLong(5, match) else ps.setNull(5, java.sql.Types.BIGINT)
                    ps.setString(6, n.protocol.name)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            log.info("neighbors.replaced device={} count={}", deviceId, unique.size)
            true
        }
    }.onFailure { log.warn("replaceAll neighbors device={} falló: {} — rollback", deviceId, it.message) }
        .getOrDefault(false)

    fun list(deviceId: Long): List<Map<String, Any?>> = runCatching {
        db.withConnection { conn ->
            conn.queryToMaps(
                """
                SELECT n.local_interface, n.remote_hostname, n.remote_port, n.protocol,
                       n.discovered_at, n.remote_device_id,
                       (n.remote_device_id IS NOT NULL) AS known_device
                FROM device_neighbors n WHERE n.device_id = ? ORDER BY n.local_interface
                """.trimIndent()
            ) { it.setLong(1, deviceId) }
        }
    }.getOrDefault(emptyList())

    /** Pares (hostname, vecino) de toda la flota — para el resumen de topología (5C). */
    fun topologySummary(limit: Int = 500): List<Map<String, Any?>> = runCatching {
        db.withConnection { conn ->
            conn.queryToMaps(
                """
                SELECT d.hostname AS local_hostname, n.local_interface, n.remote_hostname,
                       n.remote_port, n.protocol, (n.remote_device_id IS NOT NULL) AS known_device
                FROM device_neighbors n
                JOIN devices d ON d.id = n.device_id
                ORDER BY d.hostname, n.local_interface
                LIMIT ?
                """.trimIndent()
            ) { it.setInt(1, limit.coerceIn(1, 5000)) }
        }
    }.getOrDefault(emptyList())

    /** Match único o null (error #42). El hostname remoto puede venir con dominio (CDP). */
    private fun matchDeviceId(conn: Connection, remoteHostname: String): Long? {
        fun candidates(name: String): List<Long> = conn.queryToMaps(
            "SELECT id FROM devices WHERE lower(hostname) = lower(?) LIMIT 3"
        ) { it.setString(1, name) }.mapNotNull { (it["id"] as? Number)?.toLong() }

        val exact = candidates(remoteHostname)
        val matched = when {
            exact.size == 1 -> exact
            exact.isEmpty() && remoteHostname.contains('.') ->
                candidates(remoteHostname.substringBefore('.'))
            else -> exact
        }
        return when {
            matched.size == 1 -> matched.single()
            matched.size > 1 -> {
                log.warn(
                    "neighbors.match.ambiguous `{}` tiene {} candidatos en inventario — remote_device_id NULL",
                    remoteHostname, matched.size,
                )
                null
            }
            else -> null
        }
    }
}
