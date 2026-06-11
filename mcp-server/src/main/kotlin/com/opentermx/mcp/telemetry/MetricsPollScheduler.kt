package com.opentermx.mcp.telemetry

import com.opentermx.ai.context.VendorDetector
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.exec.SessionCommandRunner
import com.opentermx.mcp.handlers.toNetVendor
import com.opentermx.netparsers.ParseResult
import com.opentermx.netparsers.ParserRegistry
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory

/**
 * Scheduler de muestreo periódico (regla #3 de persistencia, Fase 3): cada
 * [pollIntervalMinutes] recorre las sesiones de terminal ACTIVAS, ejecuta el comando
 * de interfaces del vendor (vía el runner de Fase 1: whitelist interna, des-paginación,
 * mutex por sesión), parsea y persiste en `interface_metrics`, detectando transiciones
 * de enlace (=> `link_events`).
 *
 * **Desviación deliberada del spec**: el scheduler NO abre sesiones nuevas a devices
 * del inventario — `open_session` es una tool mutativa con approval gate humano, y un
 * proceso background que abre conexiones SSH cada 5 minutos sin operador violaría esa
 * invariante (además de spamear el diálogo). Muestrea lo que el operador ya tiene
 * abierto; cuando exista un mecanismo de credenciales headless aprobado, se extiende.
 *
 * Resiliencia:
 *  - concurrencia limitada por [Semaphore] (default 5 — error de saturar N devices a la vez);
 *  - una sesión que falla 3 polls seguidos entra en backoff exponencial 5→10→20 min
 *    (tope 60) con evento en el log;
 *  - mantenimiento diario: partición del mes próximo + drop por retención.
 */
class MetricsPollScheduler(
    private val store: TelemetryStore,
    private val runner: SessionCommandRunner,
    private val pollIntervalMinutes: () -> Int = { 5 },
    private val retentionDays: () -> Int = { 90 },
    maxConcurrent: Int = 5,
    private val commandTimeoutMillis: Long = 30_000,
    /** Extras del mantenimiento diario (Fase 5D: regeneración de docs RAG). */
    private val onDailyMaintenance: () -> Unit = {},
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val semaphore = Semaphore(maxConcurrent)
    private val failures = ConcurrentHashMap<String, FailureState>()
    private var job: Job? = null
    private var lastMaintenanceDay: LocalDate? = null

    private data class FailureState(val consecutive: Int, val skipUntilMillis: Long)

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            log.info("Scheduler de telemetría iniciado (intervalo {} min)", pollIntervalMinutes())
            while (true) {
                delay(pollIntervalMinutes().coerceAtLeast(1) * 60_000L)
                runCatching { pollOnce() }
                    .onFailure { log.warn("poll de telemetría falló: {}", it.message) }
                runCatching { maintenanceIfDue() }
                    .onFailure { log.warn("mantenimiento de telemetría falló: {}", it.message) }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Una pasada de muestreo sobre las sesiones activas. Pública para tests. */
    suspend fun pollOnce() {
        if (!store.isAvailable()) {
            log.debug("BD no disponible — poll de telemetría salteado")
            return
        }
        val sessions = SessionRegistry.activeSessions()
        if (sessions.isEmpty()) return
        val jobs = mutableListOf<Job>()
        kotlinx.coroutines.coroutineScope {
            for (descriptor in sessions) {
                val key = descriptor.id.value
                val state = failures[key]
                if (state != null && System.currentTimeMillis() < state.skipUntilMillis) continue
                jobs += launch {
                    semaphore.withPermit { pollSession(descriptor.id) }
                }
            }
        }
        log.debug("Poll de telemetría: {} sesiones muestreadas", jobs.size)
    }

    private suspend fun pollSession(sessionId: SessionId) {
        val key = sessionId.value
        try {
            val metadata = SessionRegistry.metadataOf(sessionId) ?: return
            if (SessionRegistry.sinkOf(sessionId) == null) return
            val sample = SessionRegistry.lastLinesOf(sessionId, 64).joinToString("\n")
            if (sample.isBlank()) return
            val aiVendor = VendorDetector.detect(sample)
            val netVendor = aiVendor.toNetVendor()
            val command = ParserRegistry.interfaceStatsCommand(netVendor) ?: return

            val run = runner.run(sessionId, aiVendor, command, commandTimeoutMillis)
            val parsed = ParserRegistry.forCommand(netVendor, command)?.parse(run.output)
            val interfaces = when (parsed) {
                is ParseResult.Success -> parsed.data
                is ParseResult.PartialSuccess -> parsed.data
                else -> {
                    recordFailure(key, "parse Failure")
                    return
                }
            }
            store.persistSample(metadata, netVendor, interfaces)
            failures.remove(key)
        } catch (e: Exception) {
            recordFailure(key, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun recordFailure(key: String, reason: String) {
        val next = (failures[key]?.consecutive ?: 0) + 1
        if (next >= FAILURES_BEFORE_BACKOFF) {
            // 3 fallos => 5 min; luego 10, 20, ... tope 60.
            val steps = next - FAILURES_BEFORE_BACKOFF
            val backoffMin = (BASE_BACKOFF_MINUTES shl steps.coerceAtMost(4)).coerceAtMost(MAX_BACKOFF_MINUTES)
            failures[key] = FailureState(next, System.currentTimeMillis() + backoffMin * 60_000L)
            log.warn("Sesión {} falló {} polls seguidos ({}): backoff {} min", key, next, reason, backoffMin)
        } else {
            failures[key] = FailureState(next, 0)
            log.debug("Sesión {} falló poll ({}): intento {}", key, reason, next)
        }
    }

    /** Mantenimiento una vez por día UTC. Pública para tests. */
    fun maintenanceIfDue() {
        val today = LocalDate.now(ZoneOffset.UTC)
        if (lastMaintenanceDay == today) return
        val db = store.db() ?: return
        db.maintenance.runDaily(retentionDays())
        runCatching { onDailyMaintenance() }
            .onFailure { log.warn("extra de mantenimiento diario falló: {}", it.message) }
        lastMaintenanceDay = today
    }

    companion object {
        const val FAILURES_BEFORE_BACKOFF = 3
        const val BASE_BACKOFF_MINUTES = 5
        const val MAX_BACKOFF_MINUTES = 60
    }
}
