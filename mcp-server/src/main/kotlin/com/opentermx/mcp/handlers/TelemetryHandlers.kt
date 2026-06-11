package com.opentermx.mcp.handlers

import com.opentermx.ai.audit.AiAuditEntry
import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.ai.context.VendorDetector
import com.opentermx.ai.safety.CredentialRedactor
import com.opentermx.ai.safety.RiskLevel
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.exec.SessionCommandRunner
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.INVALID_ARGUMENT
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.NOT_FOUND
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.UNAVAILABLE
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions
import com.opentermx.netparsers.InterfaceStats
import com.opentermx.netparsers.ParseResult
import com.opentermx.netparsers.ParserRegistry
import com.opentermx.netparsers.PortStatus
import com.opentermx.netparsers.toToolOutput
import java.util.UUID

/**
 * Tools de telemetría de alto nivel (Fase 2): eligen el comando de interfaces según el
 * vendor, lo ejecutan por la infraestructura de la Fase 1 ([SessionCommandRunner] con
 * prompt-detection/des-paginación/mutex) y devuelven JSON canónico parseado por
 * `net-parsers`. Comparten ejecución vía [TelemetryExecutor]; las tres son de lectura
 * pura sobre el device, sin approval gate — los comandos son los del catálogo interno
 * (nunca input del cliente) e igualmente quedan auditados.
 */
internal class TelemetryExecutor(
    private val runner: SessionCommandRunner,
    private val auditLog: AiAuditLog,
    private val redactor: CredentialRedactor,
) {
    data class Execution(
        val sessionId: SessionId,
        val netVendor: com.opentermx.netparsers.Vendor,
        val command: String,
        val result: ParseResult<List<InterfaceStats>>,
        val cleanOutput: String,
        val timedOut: Boolean,
    )

    /** Detecta vendor, resuelve comando, ejecuta, parsea y audita. Lanza McpToolException. */
    suspend fun collect(sessionIdRaw: String, interfaceName: String?, toolName: String): Execution {
        val sessionId = SessionId(sessionIdRaw)
        val metadata = SessionRegistry.metadataOf(sessionId)
            ?: throw McpToolException(NOT_FOUND, "Sesión `$sessionIdRaw` no encontrada en el registro")
        SessionRegistry.sinkOf(sessionId)
            ?: throw McpToolException(UNAVAILABLE, "Sesión `$sessionIdRaw` sin sink: no es inyectable")

        val sample = SessionRegistry.lastLinesOf(sessionId, VENDOR_SAMPLE_LINES).joinToString("\n")
        val aiVendor = if (sample.isBlank()) com.opentermx.ai.context.Vendor.UNKNOWN
        else VendorDetector.detect(sample)
        val netVendor = aiVendor.toNetVendor()
        val command = ParserRegistry.interfaceStatsCommand(netVendor, interfaceName)
            ?: throw McpToolException(
                INVALID_ARGUMENT,
                "Vendor `${aiVendor.displayName}` sin soporte de telemetría de interfaces " +
                    "(o no detectado). Usá `run_readonly_command` con el comando explícito.",
            )

        val run = try {
            runner.run(sessionId, aiVendor, command, RUN_TIMEOUT_MILLIS)
        } catch (e: SessionCommandRunner.SessionGoneException) {
            throw McpToolException(UNAVAILABLE, e.message ?: "Sesión no disponible")
        }
        val clean = redactor.redact(run.output, aiVendor)
        val parser = ParserRegistry.forCommand(netVendor, command)
        val parsed = parser?.parse(run.output)
            ?: ParseResult.Failure("sin parser para `$command` en $netVendor", run.output.take(500))

        auditLog.append(
            AiAuditEntry(
                timestampMillis = System.currentTimeMillis(),
                sessionId = "${sessionId.value}#${UUID.randomUUID()}",
                host = metadata.host,
                vendor = aiVendor.takeIf { it != com.opentermx.ai.context.Vendor.UNKNOWN }?.displayName,
                prompt = "(mcp $toolName)",
                commands = listOf(command),
                commandRisks = listOf(RiskLevel.SAFE),
                executedCount = 1,
                skippedCount = 0,
                failedCount = 0,
                rejected = false,
                outputTail = clean.take(AUDIT_EXCERPT_CHARS),
            )
        )
        return Execution(sessionId, netVendor, command, parsed, clean, run.timedOut)
    }

    companion object {
        const val RUN_TIMEOUT_MILLIS = 30_000L
        const val AUDIT_EXCERPT_CHARS = 2048
        private const val VENDOR_SAMPLE_LINES = 64
    }
}

/** Mapeo detección de CLI (ai-assistant) → vendor canónico de telemetría (net-parsers). */
internal fun com.opentermx.ai.context.Vendor.toNetVendor(): com.opentermx.netparsers.Vendor =
    when (this) {
        com.opentermx.ai.context.Vendor.CISCO_IOS -> com.opentermx.netparsers.Vendor.CISCO_IOS
        com.opentermx.ai.context.Vendor.CISCO_IOS_XE -> com.opentermx.netparsers.Vendor.CISCO_IOSXE
        com.opentermx.ai.context.Vendor.CISCO_NX_OS -> com.opentermx.netparsers.Vendor.CISCO_NXOS
        com.opentermx.ai.context.Vendor.ARUBA_OS -> com.opentermx.netparsers.Vendor.ARUBA_AOSCX
        com.opentermx.ai.context.Vendor.FORTINET_FORTIOS -> com.opentermx.netparsers.Vendor.FORTINET
        com.opentermx.ai.context.Vendor.HUAWEI_VRP -> com.opentermx.netparsers.Vendor.HUAWEI_VRP
        com.opentermx.ai.context.Vendor.HPE_COMWARE -> com.opentermx.netparsers.Vendor.HPE_COMWARE
        com.opentermx.ai.context.Vendor.MIKROTIK_ROUTEROS -> com.opentermx.netparsers.Vendor.MIKROTIK
        com.opentermx.ai.context.Vendor.JUNIPER_JUNOS -> com.opentermx.netparsers.Vendor.JUNIPER_JUNOS
        com.opentermx.ai.context.Vendor.UNKNOWN -> com.opentermx.netparsers.Vendor.UNKNOWN
    }

/**
 * `get_interface_stats`: estadísticas estructuradas de interfaces. Ante un output que
 * el parser no entiende devuelve `parsed: false` + el texto crudo (regla de oro Fase 2:
 * el LLM al menos puede leerlo). Con `persist=true` (default) y la BD disponible, la
 * muestra se inserta en `interface_metrics` (Fase 3) — sin BD degrada con gracia y
 * devuelve `persisted: false`.
 */
class GetInterfaceStatsHandler(
    runner: SessionCommandRunner,
    auditLog: AiAuditLog = AiAuditLog(),
    redactor: CredentialRedactor = CredentialRedactor(),
    private val store: com.opentermx.mcp.telemetry.TelemetryStore? = null,
) : ToolHandler {

    private val executor = TelemetryExecutor(runner, auditLog, redactor)

    override val definition: ToolDef = ToolDefinitions.GET_INTERFACE_STATS

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val sessionId = Args.requireString(args, "sessionId")
        val interfaceName = Args.optionalString(args, "interfaceName")
        val persist = args["persist"] as? Boolean ?: true
        val exec = executor.collect(sessionId, interfaceName, "get_interface_stats")

        val base = when (val r = exec.result) {
            is ParseResult.Failure -> linkedMapOf<String, Any?>(
                "parsed" to false,
                "vendor" to exec.netVendor.name,
                "interfaces" to emptyList<Any?>(),
                "warnings" to listOf(r.reason),
                "rawOutput" to exec.cleanOutput,
            )
            else -> LinkedHashMap(filterByName(exec.result, interfaceName).toToolOutput(exec.netVendor))
        }
        base["command"] = exec.command
        base["timedOut"] = exec.timedOut
        base["persisted"] = persistIfPossible(persist, exec, sessionId)
        return base
    }

    /** Persistencia con degradación: BD ausente o caída => false, jamás un error. */
    private fun persistIfPossible(
        persist: Boolean,
        exec: TelemetryExecutor.Execution,
        sessionIdRaw: String,
    ): Boolean {
        if (!persist || store == null) return false
        val interfaces = when (val r = exec.result) {
            is ParseResult.Success -> r.data
            is ParseResult.PartialSuccess -> r.data
            is ParseResult.Failure -> return false
        }
        val metadata = SessionRegistry.metadataOf(com.opentermx.common.session.SessionId(sessionIdRaw))
            ?: return false
        return store.persistSample(metadata, exec.netVendor, interfaces) > 0
    }

    private fun filterByName(
        result: ParseResult<List<InterfaceStats>>,
        name: String?,
    ): ParseResult<List<InterfaceStats>> {
        if (name == null) return result
        fun filter(list: List<InterfaceStats>) = list.filter { it.name.equals(name, ignoreCase = true) }
        return when (result) {
            is ParseResult.Success -> {
                val kept = filter(result.data)
                if (kept.isEmpty()) ParseResult.PartialSuccess(
                    kept, listOf("la interfaz `$name` no aparece en el output del equipo"),
                ) else ParseResult.Success(kept)
            }
            is ParseResult.PartialSuccess -> ParseResult.PartialSuccess(filter(result.data), result.warnings)
            is ParseResult.Failure -> result
        }
    }
}

/**
 * `get_link_status`: proyección liviana (nombre/admin/oper/lastFlap) pensada para
 * contextos chicos. `onlyProblems=true` filtra a interfaces caídas o err-disabled
 * con admin UP — "lo que debería estar andando y no anda".
 */
class GetLinkStatusHandler(
    runner: SessionCommandRunner,
    auditLog: AiAuditLog = AiAuditLog(),
    redactor: CredentialRedactor = CredentialRedactor(),
) : ToolHandler {

    private val executor = TelemetryExecutor(runner, auditLog, redactor)

    override val definition: ToolDef = ToolDefinitions.GET_LINK_STATUS

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val sessionId = Args.requireString(args, "sessionId")
        val onlyProblems = args["onlyProblems"] as? Boolean ?: false
        val exec = executor.collect(sessionId, null, "get_link_status")

        val interfaces = when (val r = exec.result) {
            is ParseResult.Success -> r.data
            is ParseResult.PartialSuccess -> r.data
            is ParseResult.Failure -> return linkedMapOf(
                "parsed" to false,
                "vendor" to exec.netVendor.name,
                "links" to emptyList<Any?>(),
                "warnings" to listOf(r.reason),
                "rawOutput" to exec.cleanOutput,
            )
        }
        val links = interfaces
            .filter {
                !onlyProblems || (
                    it.adminStatus == PortStatus.UP &&
                        (it.operStatus == PortStatus.DOWN || it.operStatus == PortStatus.ERR_DISABLED)
                    )
            }
            .map {
                linkedMapOf(
                    "name" to it.name,
                    "adminStatus" to it.adminStatus.name,
                    "operStatus" to it.operStatus.name,
                    "lastFlap" to it.lastFlap,
                )
            }
        return linkedMapOf(
            "parsed" to true,
            "vendor" to exec.netVendor.name,
            "links" to links,
            "onlyProblems" to onlyProblems,
        )
    }
}

/**
 * `get_bandwidth_utilization`: tasas + % de utilización por interfaz.
 *
 *  - `method: device_rate` cuando el equipo reporta tasas (`input rate ... bits/sec`);
 *  - `method: counter_delta` cuando no: segunda muestra a los `sampleIntervalSeconds`
 *    y delta de bytes (`raw.rxBytes/txBytes` de los parsers) ×8/intervalo. Delta
 *    negativo = wrap/reset del contador (error #15): la interfaz reporta null + warning,
 *    jamás un negativo.
 */
class GetBandwidthUtilizationHandler(
    runner: SessionCommandRunner,
    auditLog: AiAuditLog = AiAuditLog(),
    redactor: CredentialRedactor = CredentialRedactor(),
    /** Inyectable para tests: escala de `sampleIntervalSeconds` a ms reales de espera. */
    private val sampleDelayMillis: (Int) -> Long = { it * 1000L },
) : ToolHandler {

    private val executor = TelemetryExecutor(runner, auditLog, redactor)

    override val definition: ToolDef = ToolDefinitions.GET_BANDWIDTH_UTILIZATION

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val sessionId = Args.requireString(args, "sessionId")
        val interfaceName = Args.optionalString(args, "interfaceName")
        val intervalSeconds = Args.optionalInt(args, "sampleIntervalSeconds", default = 10, min = 5, max = 30)

        val first = executor.collect(sessionId, interfaceName, "get_bandwidth_utilization")
        val firstIfaces = dataOf(first.result)
            ?: throw McpToolException(
                INVALID_ARGUMENT,
                "No pude parsear el output de `${first.command}` para calcular utilización. " +
                    "Probá `get_interface_stats` para ver el crudo.",
            )
        val warnings = mutableListOf<String>()

        val hasDeviceRates = firstIfaces.any { it.inputRateBps != null || it.outputRateBps != null }
        val rows: List<Map<String, Any?>> = if (hasDeviceRates) {
            firstIfaces.map { utilizationRow(it.name, it.speedBps, it.inputRateBps, it.outputRateBps) }
        } else {
            kotlinx.coroutines.delay(sampleDelayMillis(intervalSeconds))
            val second = executor.collect(sessionId, interfaceName, "get_bandwidth_utilization")
            val secondIfaces = dataOf(second.result) ?: emptyList()
            val firstByName = firstIfaces.associateBy { it.name }
            secondIfaces.map { now ->
                val before = firstByName[now.name]
                val rxDelta = deltaBytes(before, now, "rxBytes")
                val txDelta = deltaBytes(before, now, "txBytes")
                if (rxDelta == null || txDelta == null) {
                    warnings += "`${now.name}`: contador de bytes ausente o reiniciado (wrap/reset) " +
                        "en al menos una dirección — esa tasa va como null"
                }
                utilizationRow(
                    now.name, now.speedBps,
                    rxDelta?.let { it * 8 / intervalSeconds },
                    txDelta?.let { it * 8 / intervalSeconds },
                )
            }
        }
        val filtered = if (interfaceName != null) {
            rows.filter { (it["name"] as String).equals(interfaceName, ignoreCase = true) }
        } else rows

        val out = linkedMapOf<String, Any?>(
            "method" to if (hasDeviceRates) "device_rate" else "counter_delta",
            "vendor" to first.netVendor.name,
            "interfaces" to filtered,
        )
        if (warnings.isNotEmpty()) out["warnings"] = warnings
        return out
    }

    private fun dataOf(result: ParseResult<List<InterfaceStats>>): List<InterfaceStats>? = when (result) {
        is ParseResult.Success -> result.data
        is ParseResult.PartialSuccess -> result.data
        is ParseResult.Failure -> null
    }

    /** Delta de bytes entre muestras; null si falta el contador o si es negativo (wrap/reset). */
    private fun deltaBytes(before: InterfaceStats?, now: InterfaceStats, key: String): Long? {
        val a = before?.raw?.get(key)?.toLongOrNull() ?: return null
        val b = now.raw[key]?.toLongOrNull() ?: return null
        val delta = b - a
        return if (delta < 0) null else delta
    }

    private fun utilizationRow(
        name: String,
        speedBps: Long?,
        inRate: Long?,
        outRate: Long?,
    ): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "speedBps" to speedBps,
        "inputRateBps" to inRate,
        "outputRateBps" to outRate,
        "utilizationInPct" to pct(inRate, speedBps),
        "utilizationOutPct" to pct(outRate, speedBps),
    )

    private fun pct(rate: Long?, speed: Long?): Double? {
        if (rate == null || speed == null || speed <= 0) return null
        return Math.round(rate.toDouble() / speed * 100 * 100) / 100.0
    }
}
