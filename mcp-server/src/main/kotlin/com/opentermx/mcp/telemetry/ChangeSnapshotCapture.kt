package com.opentermx.mcp.telemetry

import com.opentermx.ai.context.Vendor
import com.opentermx.common.ai.SessionMetadata
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.exec.SessionCommandRunner
import com.opentermx.mcp.handlers.toNetVendor
import com.opentermx.mcp.snapshots.Snapshot
import com.opentermx.mcp.snapshots.SnapshotDiffer
import com.opentermx.telemetrydb.ConfigSanitizer
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

/**
 * Snapshots PRE/POST-CHANGE de la running-config alrededor de una ejecución aprobada de
 * `propose_commands` (regla 2 de la Fase 3 — pendiente hasta ahora).
 *
 * Reglas:
 *  - El comando de captura es del CATÁLOGO INTERNO por vendor (`show running-config`,
 *    `display current-configuration`, `/export`, …) — jamás input del cliente, igual
 *    que las tools de telemetría.
 *  - **Best-effort, jamás bloquea el cambio**: la BD ausente, un vendor sin comando o
 *    un timeout degradan a "sin snapshot" con warning — la ejecución aprobada por el
 *    operador NO se aborta por un problema de telemetría.
 *  - Un timeout NO persiste: un diff calculado sobre una config parcial es basura
 *    (peor que no tener nada — sugeriría cambios que no existieron).
 *  - El diff se calcula sobre el texto YA SANITIZADO (lo mismo que se persiste): la
 *    rotación de un secreto redactado no aparece como cambio fantasma.
 *  - Mismo [SessionCommandRunner] compartido: la captura se encola por el mutex de la
 *    sesión, no compite con el operador (error #29/#34).
 */
class ChangeSnapshotCapture(
    private val store: TelemetryStore,
    private val runner: SessionCommandRunner,
    private val captureTimeoutMillis: Long = DEFAULT_CAPTURE_TIMEOUT_MILLIS,
    /** Espera tras el último comando inyectado antes de capturar el estado post. */
    private val postChangeSettleMillis: Long = DEFAULT_POST_SETTLE_MILLIS,
) {

    data class Captured(
        val snapshotId: Long,
        val deviceId: Long,
        val sanitizedConfig: String,
    )

    private val log = LoggerFactory.getLogger(javaClass)

    /** Hay BD y el vendor tiene comando de running-config: vale la pena intentar. */
    fun isAvailable(vendor: Vendor): Boolean =
        store.db() != null && runningConfigCommand(vendor) != null

    suspend fun capturePre(sessionId: SessionId, metadata: SessionMetadata, vendor: Vendor): Captured? =
        capture(sessionId, metadata, vendor, trigger = "pre_change")

    /** Settle primero: el equipo puede tardar en aplicar el último comando del cambio. */
    suspend fun capturePost(sessionId: SessionId, metadata: SessionMetadata, vendor: Vendor): Captured? {
        if (postChangeSettleMillis > 0) delay(postChangeSettleMillis)
        return capture(sessionId, metadata, vendor, trigger = "post_change")
    }

    /**
     * Diff pre→post persistido en `config_diffs`, o null si la config no cambió (hash
     * idéntico tras sanitizar) o si no se pudo persistir.
     */
    fun persistDiff(pre: Captured, post: Captured, vendor: Vendor): Long? {
        if (pre.deviceId != post.deviceId) return null
        val db = store.db() ?: return null
        val result = SnapshotDiffer.diff(
            asDifferSnapshot("pre_change", pre.sanitizedConfig),
            asDifferSnapshot("post_change", post.sanitizedConfig),
            deviceType = differDeviceType(vendor),
        )
        if (result.addedLines.isEmpty() && result.removedLines.isEmpty()) {
            log.debug("pre/post-change sin diferencias (device={}) — no se persiste diff", pre.deviceId)
            return null
        }
        return db.snapshots.insertDiff(
            deviceId = pre.deviceId,
            fromSnapshotId = pre.snapshotId,
            toSnapshotId = post.snapshotId,
            unifiedDiff = renderDiff(result),
            linesAdded = result.addedLines.size,
            linesRemoved = result.removedLines.size,
        )
    }

    // ------------------------------------------------------------------ internals

    private suspend fun capture(
        sessionId: SessionId,
        metadata: SessionMetadata,
        vendor: Vendor,
        trigger: String,
    ): Captured? = runCatching {
        val db = store.db() ?: return null
        val command = runningConfigCommand(vendor) ?: return null
        val host = metadata.host ?: return null

        val run = runner.run(sessionId, vendor, command, captureTimeoutMillis)
        if (run.timedOut) {
            log.warn(
                "snapshot {} de {} abortado: `{}` no terminó en {} ms — una config parcial no sirve",
                trigger, sessionId.value, command, captureTimeoutMillis,
            )
            return null
        }
        if (run.output.isBlank()) return null

        val deviceId = db.devices.upsert(
            hostname = host,
            mgmtAddress = host,
            port = metadata.port ?: 22,
            protocol = metadata.protocol,
            vendor = vendor.toNetVendor(),
        ) ?: return null
        val snapshotId = db.snapshots.insert(deviceId, trigger, run.output) ?: return null
        log.info(
            "config_snapshot {} persistido: device={} snapshot={} ({} líneas{})",
            trigger, deviceId, snapshotId, run.output.lineSequence().count(),
            if (run.truncated) ", TRUNCADO a ${SessionCommandRunner.MAX_OUTPUT_CHARS} chars" else "",
        )
        Captured(snapshotId, deviceId, ConfigSanitizer.sanitize(run.output))
    }.onFailure {
        log.warn("snapshot {} de {} falló: {} — el cambio sigue igual", trigger, sessionId.value, it.message)
    }.getOrNull()

    private fun asDifferSnapshot(label: String, sanitized: String) = Snapshot(
        id = label,
        operationId = null,
        sessionId = label,
        deviceAlias = null,
        snapshotType = "running_config",
        timestampMillis = 0L,
        contentHash = Snapshot.hashOf(sanitized),
        content = sanitized,
    )

    /** Texto legible para `config_diffs.unified_diff`, con secciones cuando las hay. */
    private fun renderDiff(result: SnapshotDiffer.DiffResult): String {
        val text = buildString {
            appendLine("--- pre_change")
            appendLine("+++ post_change")
            if (result.sections.isNotEmpty()) {
                result.sections.forEach { section ->
                    appendLine("@@ ${section.header} (${section.change})")
                    section.removedLines.forEach { appendLine("- $it") }
                    section.addedLines.forEach { appendLine("+ $it") }
                }
            } else {
                result.removedLines.forEach { appendLine("- $it") }
                result.addedLines.forEach { appendLine("+ $it") }
            }
        }
        return if (text.length > MAX_DIFF_CHARS) text.take(MAX_DIFF_CHARS) + "\n…[truncated]" else text
    }

    private fun differDeviceType(vendor: Vendor): String? = when (vendor) {
        Vendor.CISCO_IOS, Vendor.CISCO_IOS_XE, Vendor.CISCO_NX_OS, Vendor.ARUBA_OS -> "cisco_ios"
        Vendor.HUAWEI_VRP -> "huawei_vrp"
        else -> null
    }

    companion object {
        const val DEFAULT_CAPTURE_TIMEOUT_MILLIS = 60_000L
        const val DEFAULT_POST_SETTLE_MILLIS = 1_000L
        const val MAX_DIFF_CHARS = 64 * 1024

        /**
         * Comando de running-config por vendor. `null` = sin captura para ese vendor
         * (UNKNOWN incluido: no se adivina).
         */
        fun runningConfigCommand(vendor: Vendor): String? = when (vendor) {
            Vendor.CISCO_IOS, Vendor.CISCO_IOS_XE, Vendor.CISCO_NX_OS -> "show running-config"
            Vendor.ARUBA_OS -> "show running-config"
            Vendor.HUAWEI_VRP -> "display current-configuration"
            Vendor.HPE_COMWARE -> "display current-configuration"
            Vendor.JUNIPER_JUNOS -> "show configuration"
            Vendor.MIKROTIK_ROUTEROS -> "/export"
            // FortiOS: `show` vuelca la config del scope actual; full-configuration es
            // gigante (defaults incluidos) y rompería el límite del runner.
            Vendor.FORTINET_FORTIOS -> "show"
            Vendor.UNKNOWN -> null
        }
    }
}
