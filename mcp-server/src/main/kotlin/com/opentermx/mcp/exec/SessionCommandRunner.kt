package com.opentermx.mcp.exec

import com.opentermx.ai.context.Vendor
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.session.SessionId
import com.opentermx.netparsers.OutputCleaner
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Ejecuta UN comando contra una sesión de terminal activa y captura su output con
 * detección de prompt, manejo de paginador y timeout (Fase 1 del plan de telemetría).
 *
 * Invariantes:
 *  - **Mutex por sesión** (error #29): una sesión es un recurso serial; dos comandos
 *    concurrentes al mismo socket corrompen ambas respuestas. Las llamadas se encolan.
 *  - **Timeout siempre** (error #2): nunca cuelga al cliente MCP. Al expirar devuelve
 *    el output parcial capturado con `timedOut = true`.
 *  - **Des-paginación una vez por sesión**: antes del primer comando se envía el
 *    comando del vendor (`terminal length 0`, `screen-length 0 temporary`, `no page`,
 *    `set cli screen-length 0`). Fortinet no tiene comando no-config: se confía en el
 *    auto-respond del paginador. MikroTik: se appendea `without-paging` al comando.
 *  - **Auto-respond de paginador** (error #1): si la cola del buffer muestra `--More--`
 *    o variantes, se envía un espacio CRUDO por la [com.opentermx.common.connection.Connection]
 *    de la sesión (sin newline — `CommandSink.sendLine` agregaría uno).
 *
 * Cómo detecta fin de comando: el buffer se sondea cada [pollIntervalMillis]; el comando
 * terminó cuando (a) la ventana del buffer cambió respecto del baseline pre-comando,
 * (b) quedó estable durante dos sondeos consecutivos y (c) la última línea no vacía
 * matchea el prompt-regex del vendor (override por sesión vía [setPromptOverride]).
 * El eco del comando y la línea de prompt final se remueven del output.
 */
class SessionCommandRunner(
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    private val bufferWindow: Int = DEFAULT_BUFFER_WINDOW,
    /** Bytes crudos hacia la sesión (espacio del paginador). Inyectable para tests. */
    private val rawSender: (SessionId) -> ((ByteArray) -> Unit)? = { id ->
        SessionRegistry.connectionOf(id)?.let { conn -> { bytes: ByteArray -> conn.send(bytes) } }
    },
) {

    data class RunResult(
        val output: String,
        val timedOut: Boolean,
        val truncated: Boolean,
        val durationMs: Long,
    )

    class SessionGoneException(message: String) : RuntimeException(message)

    private val log = LoggerFactory.getLogger(javaClass)
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val depaginated = ConcurrentHashMap.newKeySet<String>()
    private val promptOverrides = ConcurrentHashMap<String, Regex>()

    /** Override del prompt-regex para una sesión puntual (equipos con prompt exótico). */
    fun setPromptOverride(sessionId: SessionId, regex: Regex?) {
        if (regex == null) promptOverrides.remove(sessionId.value)
        else promptOverrides[sessionId.value] = regex
    }

    suspend fun run(
        sessionId: SessionId,
        vendor: Vendor,
        command: String,
        timeoutMillis: Long,
    ): RunResult {
        val mutex = mutexes.computeIfAbsent(sessionId.value) { Mutex() }
        return mutex.withLock {
            depaginateIfNeeded(sessionId, vendor)
            val effective = effectiveCommand(vendor, command)
            executeLocked(sessionId, vendor, effective, command, timeoutMillis)
        }
    }

    // ------------------------------------------------------------------ internals

    private suspend fun executeLocked(
        sessionId: SessionId,
        vendor: Vendor,
        commandToSend: String,
        originalCommand: String,
        timeoutMillis: Long,
    ): RunResult {
        val startedAt = System.currentTimeMillis()
        val baseline = window(sessionId)
        sendLine(sessionId, commandToSend)

        var lastWindow = baseline
        var stableOnce = false
        var timedOut = true
        val deadline = startedAt + timeoutMillis
        val prompt = promptOverrides[sessionId.value] ?: PROMPT_BY_VENDOR.getValue(vendor)

        while (System.currentTimeMillis() < deadline) {
            delay(pollIntervalMillis)
            val current = window(sessionId)
            val lastLine = current.lastOrNull { it.isNotBlank() }?.trim()

            if (lastLine != null && PAGER_TAIL.containsMatchIn(lastLine)) {
                respondPager(sessionId)
                lastWindow = current
                stableOnce = false
                continue
            }

            val changedSinceBaseline = current != baseline
            val stable = current == lastWindow
            if (changedSinceBaseline && stable && lastLine != null && prompt.containsMatchIn(lastLine)) {
                if (stableOnce) {
                    timedOut = false
                    break
                }
                stableOnce = true
            } else {
                stableOnce = false
            }
            lastWindow = current
        }

        val finalWindow = window(sessionId)
        val rawOutput = extractOutput(finalWindow, baseline, commandToSend, prompt)
        val cleaned = OutputCleaner.clean(rawOutput, commandToSend)
        val truncated = cleaned.length > MAX_OUTPUT_CHARS
        val output = if (truncated) cleaned.take(MAX_OUTPUT_CHARS) + "\n…[truncated]" else cleaned
        return RunResult(
            output = output,
            timedOut = timedOut,
            truncated = truncated,
            durationMs = System.currentTimeMillis() - startedAt,
        ).also {
            if (timedOut) {
                log.warn(
                    "run_readonly_command timeout ({} ms) en sesión {} — devuelvo parcial de {} chars",
                    timeoutMillis, sessionId.value, it.output.length,
                )
            }
            // El comando original queda para el audit del caller; acá solo trazas debug.
            log.debug("Comando `{}` en {} terminó en {} ms", originalCommand, sessionId.value, it.durationMs)
        }
    }

    /**
     * Output = lo que apareció después del eco del comando. Si el eco no está en la
     * ventana (eco deshabilitado o scrolleado), caemos al diff contra el baseline.
     * En ambos casos, la línea final de prompt se descarta.
     */
    private fun extractOutput(
        finalWindow: List<String>,
        baseline: List<String>,
        command: String,
        prompt: Regex,
    ): String {
        val echoIdx = finalWindow.indexOfLast { it.trimEnd().endsWith(command) }
        var lines = if (echoIdx >= 0) {
            finalWindow.drop(echoIdx + 1)
        } else {
            // Diff posicional: saltea el prefijo común con el baseline.
            val common = baseline.zip(finalWindow).takeWhile { (a, b) -> a == b }.count()
            finalWindow.drop(common)
        }
        while (lines.isNotEmpty() && (lines.last().isBlank() || prompt.containsMatchIn(lines.last().trim()))) {
            lines = lines.dropLast(1)
        }
        return lines.joinToString("\n")
    }

    private suspend fun depaginateIfNeeded(sessionId: SessionId, vendor: Vendor) {
        if (!depaginated.add(sessionId.value)) return
        val cmd = DEPAGINATION_COMMANDS[vendor] ?: return
        log.debug("Des-paginando sesión {} con `{}`", sessionId.value, cmd)
        runCatching {
            sendLine(sessionId, cmd)
            delay(DEPAGINATION_SETTLE_MILLIS)
        }.onFailure { e ->
            // Si falla, mejor reintentar la próxima vez que dejar la sesión paginando.
            depaginated.remove(sessionId.value)
            throw e
        }
    }

    /** MikroTik no tiene comando de des-paginación: el flag va en el propio comando. */
    private fun effectiveCommand(vendor: Vendor, command: String): String =
        if (vendor == Vendor.MIKROTIK_ROUTEROS &&
            Regex("""\bprint\b""").containsMatchIn(command) &&
            !command.contains("without-paging")
        ) "$command without-paging" else command

    private fun sendLine(sessionId: SessionId, line: String) {
        val sink = SessionRegistry.sinkOf(sessionId)
            ?: throw SessionGoneException("Sesión `${sessionId.value}` sin sink")
        val ok = runCatching { sink.sendLine(line) }.getOrDefault(false)
        if (!ok) {
            // Error #9: sesión rota a mitad de comando — error claro, no stacktrace.
            throw SessionGoneException("La sesión `${sessionId.value}` no aceptó el comando (¿conexión caída?)")
        }
    }

    private fun respondPager(sessionId: SessionId) {
        val raw = rawSender(sessionId)
        if (raw != null) {
            runCatching { raw(byteArrayOf(0x20)) }
                .onFailure { log.debug("No pude responder al paginador por raw send: {}", it.message) }
        } else {
            log.debug("Paginador detectado en {} pero la sesión no expone Connection — esperando timeout", sessionId.value)
        }
    }

    private fun window(sessionId: SessionId): List<String> =
        SessionRegistry.lastLinesOf(sessionId, bufferWindow)

    companion object {
        const val DEFAULT_POLL_INTERVAL_MILLIS = 120L
        const val DEFAULT_BUFFER_WINDOW = 4000
        const val DEPAGINATION_SETTLE_MILLIS = 350L
        const val MAX_OUTPUT_CHARS = 64 * 1024

        /** Variantes de paginador al final del buffer (error #1). */
        val PAGER_TAIL = Regex(
            """--\s?More\s?--|---- More ----|--More or \(q\)uit--|Press any key to continue""",
            RegexOption.IGNORE_CASE,
        )

        /** Comando de des-paginación por vendor. Ausente = no hay comando seguro (Fortinet, MikroTik). */
        val DEPAGINATION_COMMANDS: Map<Vendor, String> = mapOf(
            Vendor.CISCO_IOS to "terminal length 0",
            Vendor.CISCO_IOS_XE to "terminal length 0",
            Vendor.CISCO_NX_OS to "terminal length 0",
            Vendor.ARUBA_OS to "no page",
            Vendor.HUAWEI_VRP to "screen-length 0 temporary",
            // Comware 7 (Fase 6A): sintaxis propia, por sesión (se re-aplica al reconectar).
            Vendor.HPE_COMWARE to "screen-length disable",
            Vendor.JUNIPER_JUNOS to "set cli screen-length 0",
        )

        /** Prompt-regex por vendor; override por sesión vía [setPromptOverride]. */
        val PROMPT_BY_VENDOR: Map<Vendor, Regex> = run {
            val ciscoLike = Regex("""^[\w.\-@:/]+(\([\w\-]+\))?[>#]\s*$""")
            val generic = Regex("""^[\w.\-@:/~\[\]()]+\s*[>#$%\]]\s*$""")
            mapOf(
                Vendor.CISCO_IOS to ciscoLike,
                Vendor.CISCO_IOS_XE to ciscoLike,
                Vendor.CISCO_NX_OS to ciscoLike,
                Vendor.ARUBA_OS to ciscoLike,
                Vendor.JUNIPER_JUNOS to Regex("""^[\w.\-@]+[>%#]\s*$"""),
                Vendor.HUAWEI_VRP to Regex("""^[<\[][\w.\-/]+[>\]]\s*$"""),
                // Comware: <host> modo usuario, [host] system-view — mismo shape que VRP.
                Vendor.HPE_COMWARE to Regex("""^[<\[][\w.\-/]+[>\]]\s*$"""),
                Vendor.MIKROTIK_ROUTEROS to Regex("""^\[[\w@.\-/ ]+\]\s*>\s*$"""),
                Vendor.FORTINET_FORTIOS to Regex("""^[\w.\-]+\s*[#$]\s*$"""),
                Vendor.UNKNOWN to generic,
            )
        }
    }
}
