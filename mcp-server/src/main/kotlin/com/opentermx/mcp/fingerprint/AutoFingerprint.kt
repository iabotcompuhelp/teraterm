package com.opentermx.mcp.fingerprint

import com.opentermx.common.ai.SessionChange
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.telemetry.TelemetryStore
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Fingerprint AUTOMÁTICO al conectar una sesión (error #38 del catálogo de la Fase 5):
 *
 *  - corre en BACKGROUND (coroutine del [scope], dispatcher del caller): la sesión queda
 *    usable de inmediato; el fingerprint se encola detrás de lo que el operador esté
 *    haciendo gracias al mutex por sesión del runner (error #34);
 *  - **cacheado por dispositivo con TTL** (default 7 días): si el último fingerprint
 *    persistido está fresco, NO se manda ni un comando al equipo. Se re-sondea solo si
 *    venció el TTL, si el operador lo pide (`refresh_device_fingerprint`) o si el
 *    hostname del prompt difiere del guardado (posible reemplazo de hardware);
 *  - requiere BD disponible: sin PostgreSQL no hay caché ni perfil que actualizar, y
 *    sondear cada conexión sin persistir sería spamear los equipos.
 *
 * En `dryRun` del servicio las sondas corren y el resultado se loguea, pero nada se
 * persiste (modo iteración del spec).
 */
class AutoFingerprint(
    private val service: FingerprintService,
    private val store: TelemetryStore,
    private val persister: FingerprintPersister,
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean = { true },
    private val ttlDays: () -> Int = { DEFAULT_TTL_DAYS },
    /** Espera post-conexión antes de sondear: que el banner/login terminen de llegar. */
    private val settleDelayMillis: Long = DEFAULT_SETTLE_DELAY_MILLIS,
    private val settleAttempts: Int = DEFAULT_SETTLE_ATTEMPTS,
) {

    enum class Outcome {
        RAN, RAN_DRY,
        SKIPPED_DISABLED, SKIPPED_NO_DB, SKIPPED_FRESH,
        SKIPPED_NOT_READY, SKIPPED_IN_FLIGHT, SKIPPED_SESSION_GONE,
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * Suscribe al [SessionRegistry]: cada sesión que se abre dispara (tras el settle)
     * un [maybeFingerprint] en background. Cerrar el resultado des-suscribe.
     */
    fun start(): AutoCloseable = SessionRegistry.addChangeListener { change ->
        if (change is SessionChange.Opened && enabled()) {
            scope.launch {
                delay(settleDelayMillis)
                runCatching { maybeFingerprint(change.id) }
                    .onFailure { log.warn("auto-fingerprint de {} falló: {}", change.id.value, it.message) }
            }
        }
    }

    /**
     * Decide y ejecuta. Público para tests y para un eventual trigger manual de UI.
     * Idempotente por sesión: una segunda llamada concurrente sale con IN_FLIGHT.
     */
    suspend fun maybeFingerprint(sessionId: SessionId): Outcome {
        if (!enabled()) return Outcome.SKIPPED_DISABLED
        val db = store.db() ?: run {
            log.debug("auto-fingerprint: sin BD — salteado (el TTL vive en device_fingerprints)")
            return Outcome.SKIPPED_NO_DB
        }
        if (!inFlight.add(sessionId.value)) return Outcome.SKIPPED_IN_FLIGHT
        try {
            val metadata = SessionRegistry.metadataOf(sessionId) ?: return Outcome.SKIPPED_SESSION_GONE
            if (SessionRegistry.sinkOf(sessionId) == null) return Outcome.SKIPPED_SESSION_GONE
            if (!awaitSessionReady(sessionId)) {
                log.debug("auto-fingerprint: sesión {} sin output tras el settle — salteado", sessionId.value)
                return Outcome.SKIPPED_NOT_READY
            }

            val deviceId = metadata.host?.let { db.devices.findIdByMgmtAddress(it) }
            val lastFingerprint = deviceId?.let { db.fingerprints.latest(it) }
            if (lastFingerprint != null && isFresh(lastFingerprint)) {
                val mismatch = promptHostnameMismatch(sessionId, lastFingerprint["hostname"] as? String)
                if (!mismatch) {
                    log.debug(
                        "fingerprint.ttl.fresh session={} device={} takenAt={} — sin re-sondeo",
                        sessionId.value, deviceId, lastFingerprint["taken_at"],
                    )
                    return Outcome.SKIPPED_FRESH
                }
                log.warn(
                    "fingerprint.hostname.mismatch session={} guardado={} — re-sondeo forzado " +
                        "(¿reemplazo de hardware con la misma IP?)",
                    sessionId.value, lastFingerprint["hostname"],
                )
            }

            val report = service.fingerprint(sessionId, includeNeighbors = true)
            if (service.dryRun) {
                log.info(
                    "auto-fingerprint DRY-RUN session={} vendor={} model={} rol={} (nada persistido)",
                    sessionId.value, report.identity.vendor, report.identity.model, report.roleSuggestion,
                )
                return Outcome.RAN_DRY
            }
            val outcome = persister.persist(report, metadata, includeNeighbors = true)
            log.info(
                "auto-fingerprint session={} traceId={} vendor={} model={} persisted={} identityChanged={}",
                sessionId.value, report.traceId, report.identity.vendor, report.identity.model,
                outcome.persisted, outcome.identityChanged,
            )
            return Outcome.RAN
        } finally {
            inFlight.remove(sessionId.value)
        }
    }

    // ------------------------------------------------------------------ internals

    /** El buffer tiene que mostrar algo (banner/prompt) antes de mandar comandos. */
    private suspend fun awaitSessionReady(sessionId: SessionId): Boolean {
        repeat(settleAttempts) { attempt ->
            val sample = SessionRegistry.lastLinesOf(sessionId, READY_SAMPLE_LINES)
            if (sample.any { it.isNotBlank() }) return true
            if (attempt < settleAttempts - 1) delay(settleDelayMillis)
        }
        return false
    }

    /** TTL sobre `taken_at` del último fingerprint (rowToMap lo entrega como ISO-8601). */
    private fun isFresh(lastFingerprint: Map<String, Any?>): Boolean {
        val takenAt = runCatching { Instant.parse(lastFingerprint["taken_at"].toString()) }
            .getOrNull() ?: return false
        val ttl = Duration.ofDays(ttlDays().coerceAtLeast(1).toLong())
        return Instant.now().isBefore(takenAt.plus(ttl))
    }

    /**
     * `true` si el prompt actual anuncia OTRO hostname que el del fingerprint guardado
     * (error #38: banner/prompt que sugiere cambio). Conservador: si no se puede parsear
     * el prompt o el fingerprint no guardó hostname, NO se fuerza nada.
     */
    private fun promptHostnameMismatch(sessionId: SessionId, storedHostname: String?): Boolean {
        if (storedHostname.isNullOrBlank()) return false
        val lastLine = SessionRegistry.lastLinesOf(sessionId, READY_SAMPLE_LINES)
            .lastOrNull { it.isNotBlank() }?.trim() ?: return false
        val current = promptHostname(lastLine) ?: return false
        return !current.equals(storedHostname, ignoreCase = true) &&
            // CDP/LLDP y varios `show version` reportan FQDN; el prompt no.
            !storedHostname.substringBefore('.').equals(current, ignoreCase = true)
    }

    companion object {
        const val DEFAULT_TTL_DAYS = 7
        const val DEFAULT_SETTLE_DELAY_MILLIS = 8_000L
        const val DEFAULT_SETTLE_ATTEMPTS = 3
        private const val READY_SAMPLE_LINES = 64

        /** `[admin@mk-core] >` → mk-core (MikroTik; ignora `/menu` y flags). */
        private val MIKROTIK_PROMPT = Regex("""^\[[\w.\-]+@([\w.\-]+)(?:/[\w/.\-]+)?\]\s*>""")

        /** `<sw-huawei>` / `[sw-huawei]` → sw-huawei (VRP user/system view). */
        private val HUAWEI_PROMPT = Regex("""^[<\[]([\w.\-]+)[>\]]\s*$""")

        /** `sw-x#`, `sw-x>`, `fw-x #`, `sw-x(config)#` → sw-x (Cisco/Aruba/Fortinet). */
        private val GENERIC_PROMPT = Regex("""^([A-Za-z][\w.\-]*?)(?:\([\w\-]+\))?\s*[#>]\s*$""")

        /** Hostname anunciado por la línea de prompt, o null si no se reconoce. */
        fun promptHostname(promptLine: String): String? {
            MIKROTIK_PROMPT.find(promptLine)?.let { return it.groupValues[1] }
            HUAWEI_PROMPT.find(promptLine)?.let { return it.groupValues[1] }
            GENERIC_PROMPT.find(promptLine)?.let { return it.groupValues[1] }
            return null
        }
    }
}
