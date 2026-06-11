package com.opentermx.mcp.onboarding

import com.opentermx.common.ai.SessionMetadata
import com.opentermx.fingerprint.DeviceIdentity
import com.opentermx.mcp.telemetry.TelemetryStore
import com.opentermx.netparsers.Vendor
import org.slf4j.LoggerFactory

/**
 * Lógica de onboarding al conectar (Fase 6B): decide si el equipo de una sesión está o
 * no inventariado, pre-llena el alta con el fingerprint + el catálogo, y materializa el
 * alta confirmada por el operador. Toda la I/O es contra PostgreSQL — vive acá (no en la
 * UI) para ser testeable con zonky.
 *
 * Identidad dual IP + serial (error #51): un device se resuelve por `mgmt_address`, pero
 * si existe por IP y el serial del fingerprint difiere del guardado, NO se actualiza en
 * silencio — se reporta [Decision.HardwareReplacement] para que el operador decida.
 */
class OnboardingService(private val store: TelemetryStore) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Estado del equipo de la sesión respecto del inventario. */
    sealed interface Decision {
        data object DbUnavailable : Decision
        data class AlreadyInventoried(val deviceId: Long) : Decision
        data object NotInventoried : Decision
        data class HardwareReplacement(
            val deviceId: Long,
            val storedSerial: String,
            val currentSerial: String,
        ) : Decision
    }

    /**
     * @param identity fingerprint ya corrido (puede ser null/UNKNOWN si no se identificó).
     */
    fun resolve(metadata: SessionMetadata, identity: DeviceIdentity?): Decision {
        val db = store.db() ?: return Decision.DbUnavailable
        val host = metadata.host
        // SSH/Telnet: la IP de management ES la identidad. Solo las sesiones SERIAL (sin
        // IP) caen al serial del fingerprint como llave (spec 6B-6).
        val deviceId = if (!host.isNullOrBlank()) {
            db.devices.findIdByMgmtAddress(host) ?: return Decision.NotInventoried
        } else {
            identity?.serialNumbers?.firstOrNull()?.let { findBySerial(it) } ?: return Decision.NotInventoried
        }

        val currentSerial = identity?.serialNumbers?.firstOrNull()
        val storedSerial = db.devices.findById(deviceId)?.get("serial_number") as? String
        if (!currentSerial.isNullOrBlank() && !storedSerial.isNullOrBlank() &&
            !currentSerial.equals(storedSerial, ignoreCase = true)
        ) {
            log.warn(
                "onboarding.hardware.replacement device={} guardado={} actual={} (misma IP, otro serial)",
                deviceId, storedSerial, currentSerial,
            )
            return Decision.HardwareReplacement(deviceId, storedSerial, currentSerial)
        }
        return Decision.AlreadyInventoried(deviceId)
    }

    /** Pre-llenado del asistente: fingerprint + match del catálogo (error #54: qué matcheó). */
    data class Suggestion(
        val hostname: String?,
        val vendor: Vendor,
        val model: String?,
        val osVersion: String?,
        val serial: String?,
        val roleSuggestion: String?,
        val confidence: String,
        /** Match del catálogo, si lo hubo. */
        val brandId: Long?,
        val brandName: String?,
        val catalogModelId: Long?,
        val catalogModelName: String?,
        val deviceType: String?,
        val family: String?,
        val defaultMethods: List<String>,
        val readonlyProfile: String?,
        val matchedPattern: String?,
        val matchedText: String?,
    )

    fun suggestFrom(identity: DeviceIdentity?, roleSuggestion: String?): Suggestion {
        val db = store.db()
        val model = identity?.model
        val match = if (db != null && !model.isNullOrBlank()) {
            db.catalog.findMatchingModels(model).firstOrNull()
        } else null
        val metadata = match?.model?.metadataJson?.let { parseMetadata(it) } ?: emptyMap()
        return Suggestion(
            hostname = identity?.hostname,
            vendor = identity?.vendor ?: Vendor.UNKNOWN,
            model = model,
            osVersion = identity?.osVersion,
            serial = identity?.serialNumbers?.firstOrNull(),
            roleSuggestion = roleSuggestion,
            confidence = identity?.confidence?.name ?: "LOW",
            brandId = match?.model?.brandId,
            brandName = match?.model?.brandName,
            catalogModelId = match?.model?.id,
            catalogModelName = match?.model?.name,
            deviceType = match?.model?.deviceType,
            family = match?.model?.family,
            defaultMethods = match?.model?.defaultMethods ?: listOf("CLI_SSH"),
            readonlyProfile = metadata["readonlyProfile"] as? String,
            matchedPattern = match?.pattern,
            matchedText = match?.captured,
        )
    }

    /** Campos confirmados por el operador en el asistente. */
    data class Commit(
        val metadata: SessionMetadata,
        val identity: DeviceIdentity?,
        val hostname: String,
        val vendor: Vendor,
        val site: String?,
        val role: String,
        val criticality: String,
        val notes: String?,
        val catalogModelId: Long?,
        /** Métodos a habilitar (CLI_SSH siempre; el resto opt-in del operador). */
        val enabledMethods: List<String>,
        val probeId: String?,
        val traceId: String?,
        val rawExcerpt: String?,
        val operator: String = "operator",
    )

    /**
     * Materializa el alta (spec 6B-5): devices + device_profiles + management settings +
     * catalog_model_id, con el rol marcado OPERATOR (lo confirmó en el asistente).
     * Devuelve el deviceId, o null si falló / no hay BD. Best-effort secuencial — mismo
     * criterio que DeviceProfilesDialog (cada repo es transaccional puertas adentro).
     */
    fun commit(c: Commit): Long? {
        val db = store.db() ?: return null
        return runCatching {
            val deviceId = db.devices.upsert(
                hostname = c.hostname,
                mgmtAddress = c.metadata.host,
                port = c.metadata.port ?: 22,
                protocol = c.metadata.protocol,
                vendor = c.vendor,
            ) ?: run {
                // Host no-INET (serial puro): el upsert por mgmt_address no aplica. 6B con
                // serial requiere mgmt_address opcional — fuera del alcance del happy path.
                log.warn("onboarding.commit: host `{}` no persistible como device (no-INET)", c.metadata.host)
                return null
            }
            // Identidad system-owned del fingerprint (si corrió) + fila de perfil.
            if (c.identity != null && c.probeId != null) {
                db.profiles.applyFingerprint(
                    deviceId = deviceId,
                    identity = c.identity,
                    roleSuggestion = null, // el rol lo fija el operador abajo (OPERATOR)
                    probeId = c.probeId,
                    traceId = c.traceId,
                    rawExcerpt = c.rawExcerpt,
                )
            }
            // Rol y contexto confirmados por el operador (role_source=OPERATOR).
            db.profiles.updateOperatorFields(
                deviceId = deviceId,
                role = c.role,
                criticality = c.criticality,
                notes = c.notes,
                updatedBy = c.operator,
            )
            db.devices.updateSite(deviceId, c.site)
            c.catalogModelId?.let { db.catalog.assignCatalogModel(deviceId, it) }
            // Métodos de gestión: CLI_SSH habilitado; el resto según el opt-in (error #55).
            val methods = (c.enabledMethods + "CLI_SSH").distinct()
            for (method in methods) {
                db.catalog.setManagementMethod(deviceId, method, enabled = true, enabledBy = c.operator)
            }
            log.info(
                "onboarding.commit device={} host={} model={} rol={} métodos={}",
                deviceId, c.hostname, c.catalogModelId, c.role, methods,
            )
            deviceId
        }.onFailure { log.warn("onboarding.commit `{}` falló: {}", c.hostname, it.message) }.getOrNull()
    }

    // ------------------------------------------------------------------ internals

    private fun findBySerial(serial: String): Long? = runCatching {
        store.db()?.withConnection { conn ->
            conn.prepareStatement("SELECT id FROM devices WHERE serial_number = ? LIMIT 1").use { ps ->
                ps.setString(1, serial)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
            }
        }
    }.getOrNull()

    private fun parseMetadata(json: String): Map<String, Any?> = runCatching {
        @Suppress("UNCHECKED_CAST")
        com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map::class.java) as Map<String, Any?>
    }.getOrDefault(emptyMap())
}
