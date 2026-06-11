package com.opentermx.mcp.fingerprint

import com.opentermx.common.ai.SessionMetadata
import com.opentermx.mcp.telemetry.TelemetryStore
import org.slf4j.LoggerFactory

/**
 * ÚNICA vía de persistencia de un [FingerprintService.FingerprintReport] (Fase 5):
 * la comparten `refresh_device_fingerprint` y el auto-fingerprint al conectar —
 * dos implementaciones de "guardar el perfil" serían una mal (regla de reutilización
 * del spec).
 *
 * Hace, en orden: upsert del device por (host, port) de la sesión, `applyFingerprint`
 * transaccional (Fase 5B), reemplazo de vecinos, invalidación de la caché de
 * enriquecimiento y regeneración del doc RAG (Fase 5D). Degrada con gracia: sin BD,
 * sin match o host no-INET devuelve `persisted=false`, jamás lanza.
 */
class FingerprintPersister(
    private val store: TelemetryStore,
    private val views: DeviceProfileViews? = null,
    private val ragDocs: RagDocGenerator? = null,
) {

    data class Outcome(
        val persisted: Boolean,
        val deviceId: Long? = null,
        val identityChanged: Boolean = false,
    )

    private val log = LoggerFactory.getLogger(javaClass)

    fun persist(
        report: FingerprintService.FingerprintReport,
        metadata: SessionMetadata,
        includeNeighbors: Boolean,
    ): Outcome {
        if (report.matchedProbeId == null) return Outcome(persisted = false)
        val db = store.db() ?: return Outcome(persisted = false)
        val host = metadata.host ?: return Outcome(persisted = false)

        val deviceId = db.devices.upsert(
            hostname = report.identity.hostname ?: host,
            mgmtAddress = host,
            port = metadata.port ?: 22,
            protocol = metadata.protocol,
            vendor = report.identity.vendor,
        )
        if (deviceId == null) {
            log.debug("fingerprint de `{}` no persistible (host no-INET o BD caída)", host)
            return Outcome(persisted = false)
        }
        val applied = db.profiles.applyFingerprint(
            deviceId = deviceId,
            identity = report.identity,
            roleSuggestion = report.roleSuggestion,
            probeId = report.matchedProbeId,
            traceId = report.traceId,
            rawExcerpt = report.rawExcerpt,
        ) ?: return Outcome(persisted = false, deviceId = deviceId)

        if (includeNeighbors) {
            db.neighbors.replaceAll(deviceId, report.neighbors)
        }
        views?.invalidate(host)
        ragDocs?.regenerateFor(report.identity.hostname ?: host)
        return Outcome(persisted = true, deviceId = deviceId, identityChanged = applied.identityChanged)
    }
}
