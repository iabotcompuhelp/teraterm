package com.opentermx.rest

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.slf4j.LoggerFactory

/**
 * Append-only CSV de peticiones recibidas por la REST API (spec v4 § Logging).
 * Cabecera: `timestamp,method,path,status,durationMs,remoteIp,sessionId`.
 *
 * El log es opt-in: si [file] no es escribible, los fallos se silencian (warn) — los
 * endpoints REST nunca deben fallar por un problema de auditoría.
 */
class RestApiLog(private val file: Path?) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun record(entry: ApiLogEntry) {
        val target = file ?: return
        runCatching {
            Files.createDirectories(target.parent)
            val isNew = !Files.exists(target)
            val sb = StringBuilder()
            if (isNew) sb.append(HEADER).append('\n')
            sb.append(toCsv(entry)).append('\n')
            Files.writeString(
                target, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND,
            )
        }.onFailure { e ->
            log.warn("No se pudo escribir el REST API log en {}: {}", target, e.message)
        }
    }

    private fun toCsv(e: ApiLogEntry): String {
        val ts = FORMATTER.format(Instant.ofEpochMilli(e.timestampMillis))
        return listOf(
            ts, e.method, e.path, e.status.toString(),
            e.durationMs.toString(), e.remoteIp.orEmpty(), e.sessionId.orEmpty(),
        ).joinToString(",") { csvEscape(it) }
    }

    private fun csvEscape(value: String): String {
        val needs = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (!needs) value else "\"${value.replace("\"", "\"\"")}\""
    }

    companion object {
        const val HEADER = "timestamp,method,path,status,durationMs,remoteIp,sessionId"
        private val FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}

data class ApiLogEntry(
    val timestampMillis: Long,
    val method: String,
    val path: String,
    val status: Int,
    val durationMs: Long,
    val remoteIp: String?,
    val sessionId: String?,
)
