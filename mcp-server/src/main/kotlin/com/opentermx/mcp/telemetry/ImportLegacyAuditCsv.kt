package com.opentermx.mcp.telemetry

import com.opentermx.ai.audit.AiAuditEntry
import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.ai.safety.RiskLevel
import com.opentermx.mcp.handlers.toNetVendor
import com.opentermx.telemetrydb.TelemetryDb
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import org.slf4j.LoggerFactory

/**
 * Migración one-shot del audit CSV legacy (`~/.opentermx/audit-ia.csv`) a la tabla
 * `command_audit` (regla del spec Fase 3). **Idempotente**: cada fila lleva un sha256
 * determinístico de sus campos como `legacy_row_hash` (UNIQUE en la tabla) — correrla
 * N veces inserta exactamente una vez. Por eso se invoca automáticamente cada vez que
 * la BD pasa a disponible: el costo de re-ejecutar es un no-op.
 */
object ImportLegacyAuditCsv {

    data class Summary(val imported: Int, val skipped: Int, val failed: Int)

    private val log = LoggerFactory.getLogger(javaClass)

    fun run(auditLog: AiAuditLog, db: TelemetryDb): Summary {
        val entries = auditLog.read(limit = Int.MAX_VALUE)
        var imported = 0
        var skipped = 0
        var failed = 0
        for (entry in entries) {
            val readOnly = entry.prompt.contains("run_readonly_command") ||
                entry.prompt.contains("get_interface_stats") ||
                entry.prompt.contains("get_link_status") ||
                entry.prompt.contains("get_bandwidth_utilization")
            val decision = when {
                entry.rejected -> "REJECTED"
                readOnly -> "AUTO_READONLY"
                entry.skippedCount > 0 -> "PARTIAL"
                else -> "APPROVED"
            }
            val inserted = db.audit.insert(
                occurredAt = Instant.ofEpochMilli(entry.timestampMillis).atOffset(ZoneOffset.UTC),
                sessionUid = entry.sessionId,
                deviceId = null,
                source = "legacy_csv_import",
                vendor = vendorOf(entry.vendor),
                readOnly = readOnly,
                commands = entry.commands,
                rationale = entry.prompt,
                riskSafe = entry.commandRisks.count { it == RiskLevel.SAFE },
                riskConfig = entry.commandRisks.count { it == RiskLevel.CONFIG },
                riskDangerous = entry.commandRisks.count { it == RiskLevel.DANGEROUS },
                decision = decision,
                executedCount = entry.executedCount,
                rejectedCount = entry.skippedCount,
                outputExcerpt = entry.outputTail.ifBlank { null },
                legacyRowHash = rowHash(entry),
            )
            when (inserted) {
                true -> imported++
                false -> skipped++ // hash duplicado (ya importada) o BD caída
            }
        }
        // `insert` devuelve false tanto para duplicado como para error de BD; si la BD
        // está caída todos van a skipped — distinguirlo no vale otra ida a la BD acá.
        log.info(
            "Import de audit legacy: {} insertadas, {} ya existentes/omitidas (de {} filas CSV)",
            imported, skipped, entries.size,
        )
        return Summary(imported, skipped, failed)
    }

    /** Hash determinístico de la fila CSV — la identidad para la idempotencia. */
    fun rowHash(entry: AiAuditEntry): String {
        val key = listOf(
            entry.timestampMillis.toString(),
            entry.sessionId,
            entry.host.orEmpty(),
            entry.prompt,
            entry.commands.joinToString("|"),
            entry.executedCount.toString(),
            entry.rejected.toString(),
        ).joinToString("")
        return MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun vendorOf(displayName: String?): com.opentermx.netparsers.Vendor {
        if (displayName.isNullOrBlank()) return com.opentermx.netparsers.Vendor.UNKNOWN
        val ai = com.opentermx.ai.context.Vendor.entries.firstOrNull { it.displayName == displayName }
            ?: return com.opentermx.netparsers.Vendor.UNKNOWN
        return ai.toNetVendor()
    }
}
