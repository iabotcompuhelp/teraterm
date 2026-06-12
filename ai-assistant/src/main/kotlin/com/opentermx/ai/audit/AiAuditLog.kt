package com.opentermx.ai.audit

import com.opentermx.ai.safety.RiskLevel
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.slf4j.LoggerFactory

/**
 * Bitácora de auditoría del asistente IA (spec v4 § Logging y Captura, línea 216).
 *
 * Append-only CSV en `~/.opentermx/audit-ia.csv`. Una línea por bloque de comandos revisado:
 *   timestamp, sessionId, host, vendor, prompt, riskSummary, executedCount, skippedCount,
 *   failedCount, rejected, commandsJoined, outputTail
 */
class AiAuditLog(private val file: Path = defaultPath()) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(AiAuditEntry) -> Unit>()

    /**
     * Serializa la escritura del CSV. Sin esto, dos clientes MCP concurrentes
     * (`propose_commands` simultáneos) corren la carrera del check `Files.exists`/header y
     * del `writeString(APPEND)` de filas multi-write → headers duplicados y líneas
     * corruptas. El bloqueo es por instancia (todos los handlers comparten una `AiAuditLog`).
     */
    private val writeLock = Any()

    /**
     * Registra un listener que recibe cada [AiAuditEntry] apenas se persiste. Pensado para
     * que el servidor MCP pueda emitir `notifications/audit/appended` a clientes SSE.
     * Devuelve un `AutoCloseable` que desuscribe al cerrarse.
     */
    fun addAppendListener(listener: (AiAuditEntry) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun append(entry: AiAuditEntry) {
        // Solo la I/O del archivo va bajo lock; los listeners se notifican afuera para no
        // sostener el lock durante callbacks (que pueden emitir SSE, etc.).
        synchronized(writeLock) {
            runCatching {
                Files.createDirectories(file.parent)
                val isNew = !Files.exists(file)
                val sb = StringBuilder()
                if (isNew) {
                    sb.append(HEADER).append('\n')
                }
                sb.append(toCsvRow(entry)).append('\n')
                Files.writeString(
                    file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                )
            }.onFailure { e ->
                log.warn("No se pudo escribir el audit log IA en {}: {}", file, e.message)
            }
        }
        listeners.forEach { runCatching { it(entry) } }
    }

    private fun toCsvRow(e: AiAuditEntry): String {
        val ts = FORMATTER.format(Instant.ofEpochMilli(e.timestampMillis))
        val riskSummary = e.commandRisks.groupingBy { it }.eachCount().entries
            .joinToString(";") { "${it.key.name}=${it.value}" }
        val commands = e.commands.joinToString(" || ")
        val outputTail = e.outputTail.replace("\n", " ⏎ ").take(800)
        return listOf(
            ts,
            e.sessionId,
            e.host.orEmpty(),
            e.vendor.orEmpty(),
            e.prompt,
            riskSummary,
            e.executedCount.toString(),
            e.skippedCount.toString(),
            e.failedCount.toString(),
            e.rejected.toString(),
            commands,
            outputTail,
        ).joinToString(",") { csvEscape(it) }
    }

    private fun csvEscape(value: String): String {
        // Defensa de inyección de fórmulas (CSV/formula injection): campos como `prompt`,
        // `host`, `commands` y `outputTail` derivan de output de EQUIPOS (no confiable).
        // Un valor que arranca con `= + - @` es una fórmula en Excel/LibreOffice; se
        // antepone `'` para que la planilla lo trate como texto literal, no la ejecute.
        val guarded = if (value.isNotEmpty() && value.first() in FORMULA_TRIGGERS) "'$value" else value
        val needsQuote = guarded.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (!needsQuote) guarded else "\"${guarded.replace("\"", "\"\"")}\""
    }

    /**
     * Lee el log filtrando por [sessionId] (prefijo: la entrada puede tener sessionId
     * de la forma `id#executionId`), rango temporal `[sinceMillis, untilMillis]` y
     * limita a [limit] entradas (más nuevas primero).
     *
     * Si el archivo no existe devuelve lista vacía. Tolera líneas mal formadas.
     */
    fun read(
        sessionId: String? = null,
        sinceMillis: Long? = null,
        untilMillis: Long? = null,
        limit: Int = 50,
    ): List<AiAuditEntry> {
        if (!Files.exists(file)) return emptyList()
        val lines = runCatching { Files.readAllLines(file, StandardCharsets.UTF_8) }.getOrElse { return emptyList() }
        val entries = mutableListOf<AiAuditEntry>()
        for (line in lines.drop(1)) { // saltar header
            if (line.isBlank()) continue
            val entry = parseCsvRow(line) ?: continue
            if (sessionId != null && !entry.sessionId.startsWith(sessionId)) continue
            if (sinceMillis != null && entry.timestampMillis < sinceMillis) continue
            if (untilMillis != null && entry.timestampMillis > untilMillis) continue
            entries += entry
        }
        return entries.sortedByDescending { it.timestampMillis }.take(limit)
    }

    private fun parseCsvRow(row: String): AiAuditEntry? = runCatching {
        val fields = parseCsvFields(row)
        if (fields.size < 12) return null
        val ts = runCatching {
            java.time.LocalDateTime.parse(fields[0], FORMATTER).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrElse { 0L }
        val commands = fields[10].split(" || ").filter { it.isNotEmpty() }
        val risks = fields[5].split(';').mapNotNull {
            val parts = it.split('=')
            if (parts.size == 2) runCatching { RiskLevel.valueOf(parts[0]) }.getOrNull()?.let { lvl -> Pair(lvl, parts[1].toIntOrNull() ?: 0) } else null
        }.flatMap { (lvl, count) -> List(count) { lvl } }
        AiAuditEntry(
            timestampMillis = ts,
            sessionId = fields[1],
            host = fields[2].ifEmpty { null },
            vendor = fields[3].ifEmpty { null },
            prompt = fields[4],
            commands = commands,
            commandRisks = risks,
            executedCount = fields[6].toIntOrNull() ?: 0,
            skippedCount = fields[7].toIntOrNull() ?: 0,
            failedCount = fields[8].toIntOrNull() ?: 0,
            rejected = fields[9].toBooleanStrictOrNull() ?: false,
            outputTail = fields[11].replace(" ⏎ ", "\n"),
        )
    }.getOrNull()

    /** Parser CSV minimalista que respeta comillas dobles + escape `""`. */
    private fun parseCsvFields(row: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < row.length) {
            val c = row[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < row.length && row[i + 1] == '"') { sb.append('"'); i++ }
                    else { inQuotes = false }
                } else sb.append(c)
            } else {
                when (c) {
                    ',' -> { out += sb.toString(); sb.clear() }
                    '"' -> inQuotes = true
                    else -> sb.append(c)
                }
            }
            i++
        }
        out += sb.toString()
        return out
    }

    companion object {
        const val HEADER = "timestamp,sessionId,host,vendor,prompt,riskSummary,executedCount,skippedCount,failedCount,rejected,commands,outputTail"

        /** Primer carácter que una planilla interpreta como fórmula (CSV formula injection). */
        private val FORMULA_TRIGGERS = setOf('=', '+', '-', '@')
        private val FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

        @Throws(IOException::class)
        fun defaultPath(): Path = Path.of(System.getProperty("user.home"), ".opentermx", "audit-ia.csv")
    }
}

data class AiAuditEntry(
    val timestampMillis: Long,
    val sessionId: String,
    val host: String?,
    val vendor: String?,
    val prompt: String,
    val commands: List<String>,
    val commandRisks: List<RiskLevel>,
    val executedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val rejected: Boolean,
    val outputTail: String = "",
)
