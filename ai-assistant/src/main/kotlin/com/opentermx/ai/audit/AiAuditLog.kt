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

    fun append(entry: AiAuditEntry) {
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
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (!needsQuote) value else "\"${value.replace("\"", "\"\"")}\""
    }

    companion object {
        const val HEADER = "timestamp,sessionId,host,vendor,prompt,riskSummary,executedCount,skippedCount,failedCount,rejected,commands,outputTail"
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
