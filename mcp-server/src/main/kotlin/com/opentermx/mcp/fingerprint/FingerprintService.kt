package com.opentermx.mcp.fingerprint

import com.opentermx.ai.audit.AiAuditEntry
import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.ai.context.VendorDetector
import com.opentermx.ai.safety.CredentialRedactor
import com.opentermx.ai.safety.RiskLevel
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.session.SessionId
import com.opentermx.fingerprint.CliRejection
import com.opentermx.fingerprint.Confidence
import com.opentermx.fingerprint.DeviceIdentity
import com.opentermx.fingerprint.FingerprintProbe
import com.opentermx.fingerprint.ProbeRegistry
import com.opentermx.fingerprint.neighbors.NeighborEntry
import com.opentermx.fingerprint.neighbors.NeighborParserRegistry
import com.opentermx.mcp.exec.SessionCommandRunner
import com.opentermx.mcp.handlers.toNetVendor
import com.opentermx.mcp.security.ReadOnlyCommandValidator
import com.opentermx.mcp.security.ReadOnlyValidation
import com.opentermx.netparsers.ParseResult
import com.opentermx.netparsers.Vendor
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * Orquestador del fingerprinting (subfase 5A): identifica un dispositivo por su sesión
 * activa con sondas encadenadas, infiere el rol con [RoleRules] y descubre vecinos
 * LLDP/CDP/MNDP.
 *
 * Invariantes:
 *  - **Solo whitelist read-only**: cada comando de sonda pasa por el
 *    [ReadOnlyCommandValidator] de la Fase 1 contra el vendor objetivo de la sonda.
 *    Rechazado => la sonda se salta (`blocked`), JAMÁS se ejecuta por otra vía.
 *  - **Misma infraestructura de ejecución**: el [SessionCommandRunner] compartido
 *    (mutex por sesión, des-paginación, timeout). El fingerprint se encola detrás de
 *    los comandos del operador, no compite por el socket (error #34).
 *  - **NO_MATCH != SESSION_ERROR** (error #33): `% Invalid input` y demás rechazos de
 *    CLI son no-match esperados y la cadena sigue; una sesión rota aborta la cadena.
 *  - Máximo [MAX_CHAIN_ATTEMPTS] sondas cuando el vendor no se conoce de antemano.
 *  - [dryRun] (default `true` hasta la Fase 5B): los resultados NO se persisten —
 *    la bandera la consumirá la capa de persistencia; acá solo viaja en el reporte.
 *
 * Diagnóstico: todas las operaciones llevan un `traceId` corto que aparece en cada
 * evento de log (`fingerprint.probe.start/match/nomatch/error`) y en el reporte.
 */
class FingerprintService(
    private val runner: SessionCommandRunner,
    private val validator: ReadOnlyCommandValidator = ReadOnlyCommandValidator.default(),
    private val roleRules: RoleRules = RoleRules.default(),
    private val auditLog: AiAuditLog = AiAuditLog(),
    private val redactor: CredentialRedactor = CredentialRedactor(),
    /** Público: la capa de persistencia (refresh_device_fingerprint, 5C) lo consulta. */
    val dryRun: Boolean = true,
    /**
     * Pruebas ACTIVAS de rol (`show wlan summary`, `show spanning-tree summary`) cuando
     * el patrón de modelo no resolvió. Opt-in porque agrega comandos al equipo.
     */
    private val activeProbing: Boolean = false,
) {

    data class ProbeAttempt(
        val probeId: String,
        val command: String,
        /** `match` | `no_match` | `blocked` | `session_error` */
        val outcome: String,
        val detail: String? = null,
    )

    data class FingerprintReport(
        val traceId: String,
        val sessionId: String,
        val identity: DeviceIdentity,
        val roleSuggestion: String,
        val roleMatchedBy: String,
        val neighbors: List<NeighborEntry>,
        val neighborsTruncated: Boolean,
        val attempts: List<ProbeAttempt>,
        val warnings: List<String>,
        val dryRun: Boolean,
        val durationMs: Long,
        /** Sonda que identificó al equipo (columna probe_id de 5B), o null sin match. */
        val matchedProbeId: String? = null,
        /** Excerpt del output que matcheó (máx 2 KB) — para `raw_excerpt` de 5B. */
        val rawExcerpt: String? = null,
    )

    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun fingerprint(sessionId: SessionId, includeNeighbors: Boolean = true): FingerprintReport {
        val traceId = UUID.randomUUID().toString().take(8)
        val startedAt = System.currentTimeMillis()
        val warnings = mutableListOf<String>()
        val attempts = mutableListOf<ProbeAttempt>()
        val executedCommands = mutableListOf<String>()

        val sessionVendor = detectSessionVendor(sessionId)
        val probes = probeChain(sessionVendor)
        log.debug(
            "fingerprint.start traceId={} session={} vendorPrevio={} sondas={}",
            traceId, sessionId.value, sessionVendor, probes.map { it.id },
        )

        var identity: DeviceIdentity? = null
        var matchedProbeId: String? = null
        var rawExcerpt: String? = null
        var sessionBroken = false
        for (probe in probes) {
            val outcome = runProbe(traceId, sessionId, sessionVendor, probe, executedCommands)
            attempts += outcome.attempt
            when (outcome) {
                is ProbeOutcome.Match -> {
                    identity = outcome.identity
                    matchedProbeId = outcome.attempt.probeId
                    rawExcerpt = outcome.rawExcerpt
                    break
                }
                is ProbeOutcome.NoMatch, is ProbeOutcome.Blocked -> Unit // sigue la cadena
                is ProbeOutcome.SessionError -> {
                    sessionBroken = true
                    warnings += "sesión no disponible durante el fingerprint: ${outcome.attempt.detail}"
                    break
                }
            }
        }

        if (identity == null && !sessionBroken && attempts.isNotEmpty()) {
            log.warn(
                "fingerprint.unidentified traceId={} session={} — {} sondas sin match",
                traceId, sessionId.value, attempts.size,
            )
        }
        val resolved = identity ?: DeviceIdentity(
            vendor = if (sessionVendor != Vendor.UNKNOWN) sessionVendor else Vendor.UNKNOWN,
            model = null,
            osVersion = null,
            hostname = null,
            uptimeText = null,
            confidence = Confidence.LOW,
        )

        var role = roleRules.infer(resolved)
        if (activeProbing && role.role == RoleRules.FALLBACK_ROLE && identity != null && !sessionBroken) {
            activeRoleProbe(traceId, sessionId, resolved.vendor, executedCommands)?.let { role = it }
        }

        var neighbors = emptyList<NeighborEntry>()
        var neighborsTruncated = false
        if (includeNeighbors && identity != null && !sessionBroken) {
            val discovered = discoverNeighbors(traceId, sessionId, resolved.vendor, executedCommands, warnings)
            neighborsTruncated = discovered.size > MAX_NEIGHBORS
            if (neighborsTruncated) {
                warnings += "vecinos truncados a $MAX_NEIGHBORS de ${discovered.size}"
            }
            neighbors = discovered.take(MAX_NEIGHBORS)
        }

        if (executedCommands.isNotEmpty()) {
            audit(sessionId, resolved, executedCommands)
        }
        return FingerprintReport(
            traceId = traceId,
            sessionId = sessionId.value,
            identity = resolved,
            roleSuggestion = role.role,
            roleMatchedBy = role.matchedBy,
            neighbors = neighbors,
            neighborsTruncated = neighborsTruncated,
            attempts = attempts,
            warnings = warnings,
            dryRun = dryRun,
            durationMs = System.currentTimeMillis() - startedAt,
            matchedProbeId = matchedProbeId,
            rawExcerpt = rawExcerpt,
        ).also {
            log.info(
                "fingerprint.done traceId={} session={} vendor={} model={} rol={} confianza={} vecinos={} en {} ms",
                traceId, sessionId.value, it.identity.vendor, it.identity.model,
                it.roleSuggestion, it.identity.confidence, it.neighbors.size, it.durationMs,
            )
        }
    }

    // ------------------------------------------------------------------ internals

    private sealed interface ProbeOutcome {
        val attempt: ProbeAttempt

        data class Match(
            override val attempt: ProbeAttempt,
            val identity: DeviceIdentity,
            val rawExcerpt: String,
        ) : ProbeOutcome
        data class NoMatch(override val attempt: ProbeAttempt) : ProbeOutcome
        data class Blocked(override val attempt: ProbeAttempt) : ProbeOutcome
        data class SessionError(override val attempt: ProbeAttempt) : ProbeOutcome
    }

    private suspend fun runProbe(
        traceId: String,
        sessionId: SessionId,
        sessionVendor: Vendor,
        probe: FingerprintProbe,
        executedCommands: MutableList<String>,
    ): ProbeOutcome {
        val probeAiVendor = probe.vendor.toAiVendor()
        when (val verdict = validator.validate(probe.command, probeAiVendor)) {
            is ReadOnlyValidation.Rejected -> {
                log.warn(
                    "fingerprint.probe.blocked traceId={} probe={} — {}",
                    traceId, probe.id, verdict.reason,
                )
                return ProbeOutcome.Blocked(
                    ProbeAttempt(probe.id, probe.command, "blocked", verdict.reason),
                )
            }
            ReadOnlyValidation.Allowed -> Unit
        }

        // Con vendor de sesión conocido, el runner usa SU prompt-regex; en cadena a
        // ciegas se usa el genérico de UNKNOWN (matchea la mayoría de los prompts).
        val runVendor = if (sessionVendor != Vendor.UNKNOWN) sessionVendor.toAiVendor()
        else com.opentermx.ai.context.Vendor.UNKNOWN

        log.debug("fingerprint.probe.start traceId={} probe={} cmd={}", traceId, probe.id, probe.command)
        val run = try {
            runner.run(sessionId, runVendor, probe.command, PROBE_TIMEOUT_MILLIS)
        } catch (e: SessionCommandRunner.SessionGoneException) {
            log.warn("fingerprint.probe.error traceId={} probe={} — {}", traceId, probe.id, e.message)
            return ProbeOutcome.SessionError(
                ProbeAttempt(probe.id, probe.command, "session_error", e.message),
            )
        }
        executedCommands += probe.command

        if (run.timedOut) {
            // Error #32: un comando desconocido pudo dejar la CLI en un sub-prompt
            // interactivo. Ctrl-C crudo lo aborta en todas las CLIs soportadas;
            // best-effort, sin newline (un Enter podría ejecutar algo).
            runCatching { SessionRegistry.connectionOf(sessionId)?.send(byteArrayOf(0x03)) }
            log.debug("fingerprint.probe.timeout traceId={} probe={}", traceId, probe.id)
        }

        if (!probe.matches(run.output)) {
            val detail = when {
                run.timedOut -> "timeout a los $PROBE_TIMEOUT_MILLIS ms"
                CliRejection.isRejection(run.output) -> "rechazo de CLI (no-match esperado)"
                else -> "output no reconocido"
            }
            log.debug(
                "fingerprint.probe.nomatch traceId={} probe={} detail={} excerpt={}",
                traceId, probe.id, detail, run.output.take(RAW_EXCERPT_CHARS),
            )
            return ProbeOutcome.NoMatch(ProbeAttempt(probe.id, probe.command, "no_match", detail))
        }

        var identity = probe.extract(run.output)
            ?: return ProbeOutcome.NoMatch(
                ProbeAttempt(probe.id, probe.command, "no_match", "matcheó pero extract() devolvió null"),
            )

        identity = enrichIfPossible(traceId, sessionId, probe, identity, executedCommands)
        log.info(
            "fingerprint.probe.match traceId={} probe={} vendor={} model={} confianza={}",
            traceId, probe.id, identity.vendor, identity.model, identity.confidence,
        )
        return ProbeOutcome.Match(
            ProbeAttempt(probe.id, probe.command, "match"),
            identity,
            run.output.take(RAW_EXCERPT_CHARS),
        )
    }

    /** Comando secundario de la sonda (MikroTik routerboard): best-effort, jamás aborta. */
    private suspend fun enrichIfPossible(
        traceId: String,
        sessionId: SessionId,
        probe: FingerprintProbe,
        identity: DeviceIdentity,
        executedCommands: MutableList<String>,
    ): DeviceIdentity {
        val secondary = probe.secondaryCommand ?: return identity
        if (validator.validate(secondary, probe.vendor.toAiVendor()) !is ReadOnlyValidation.Allowed) {
            return identity
        }
        return runCatching {
            val run = runner.run(sessionId, identity.vendor.toAiVendor(), secondary, PROBE_TIMEOUT_MILLIS)
            executedCommands += secondary
            probe.enrich(identity, run.output)
        }.getOrElse { e ->
            log.debug("fingerprint.enrich.skip traceId={} probe={} — {}", traceId, probe.id, e.message)
            identity
        }
    }

    private suspend fun discoverNeighbors(
        traceId: String,
        sessionId: SessionId,
        vendor: Vendor,
        executedCommands: MutableList<String>,
        warnings: MutableList<String>,
    ): List<NeighborEntry> {
        val command = NeighborParserRegistry.neighborCommand(vendor) ?: return emptyList()
        val aiVendor = vendor.toAiVendor()
        if (validator.validate(command, aiVendor) !is ReadOnlyValidation.Allowed) {
            warnings += "comando de vecinos `$command` rechazado por la whitelist — topología omitida"
            return emptyList()
        }
        val run = try {
            runner.run(sessionId, aiVendor, command, NEIGHBOR_TIMEOUT_MILLIS)
        } catch (e: SessionCommandRunner.SessionGoneException) {
            warnings += "sesión no disponible al descubrir vecinos: ${e.message}"
            return emptyList()
        }
        executedCommands += command
        val parser = NeighborParserRegistry.forCommand(vendor, command)
            ?: return emptyList()
        return when (val result = parser.parse(run.output)) {
            is ParseResult.Success -> result.data
            is ParseResult.PartialSuccess -> {
                warnings += result.warnings
                result.data
            }
            is ParseResult.Failure -> {
                log.debug(
                    "fingerprint.neighbors.unparsed traceId={} vendor={} — {}",
                    traceId, vendor, result.reason,
                )
                warnings += "vecinos sin parsear: ${result.reason}"
                emptyList()
            }
        }
    }

    /**
     * Prueba activa de rol (error #36: un switch L3 responde comandos "de router", el
     * patrón de modelo puede no alcanzar). Solo familia Cisco, solo comandos que pasen
     * la whitelist, best-effort.
     */
    private suspend fun activeRoleProbe(
        traceId: String,
        sessionId: SessionId,
        vendor: Vendor,
        executedCommands: MutableList<String>,
    ): RoleRules.Inference? {
        if (vendor !in CISCO_FAMILY) return null
        val aiVendor = vendor.toAiVendor()
        for ((command, role, evidence) in ACTIVE_ROLE_PROBES) {
            if (validator.validate(command, aiVendor) !is ReadOnlyValidation.Allowed) continue
            val run = runCatching { runner.run(sessionId, aiVendor, command, PROBE_TIMEOUT_MILLIS) }
                .getOrNull() ?: return null
            executedCommands += command
            if (!CliRejection.isRejection(run.output) && evidence.containsMatchIn(run.output)) {
                log.info("fingerprint.role.active traceId={} cmd={} rol={}", traceId, command, role)
                return RoleRules.Inference(role, "activeProbe:$command")
            }
        }
        return null
    }

    /** Vendor declarado por la sesión (detección existente sobre el buffer). */
    private fun detectSessionVendor(sessionId: SessionId): Vendor {
        val sample = SessionRegistry.lastLinesOf(sessionId, VENDOR_SAMPLE_LINES).joinToString("\n")
        if (sample.isBlank()) return Vendor.UNKNOWN
        return VendorDetector.detect(sample).toNetVendor()
    }

    /** Vendor conocido => SOLO su sonda; desconocido => cadena acotada (error #38 manda TTL en 5B). */
    private fun probeChain(sessionVendor: Vendor): List<FingerprintProbe> {
        ProbeRegistry.forVendor(sessionVendor)?.let { return listOf(it) }
        return ProbeRegistry.all().take(MAX_CHAIN_ATTEMPTS)
    }

    private fun audit(sessionId: SessionId, identity: DeviceIdentity, commands: List<String>) {
        val metadata = SessionRegistry.metadataOf(sessionId) ?: return
        runCatching {
            auditLog.append(
                AiAuditEntry(
                    timestampMillis = System.currentTimeMillis(),
                    sessionId = "${sessionId.value}#${UUID.randomUUID()}",
                    host = metadata.host,
                    vendor = identity.vendor.takeIf { it != Vendor.UNKNOWN }?.name,
                    prompt = "(mcp fingerprint)",
                    commands = commands,
                    commandRisks = commands.map { RiskLevel.SAFE },
                    executedCount = commands.size,
                    skippedCount = 0,
                    failedCount = 0,
                    rejected = false,
                    outputTail = redactor.redact(identity.toJson().toString(), identity.vendor.toAiVendor())
                        .take(AUDIT_EXCERPT_CHARS),
                )
            )
        }.onFailure { log.warn("No pude auditar el fingerprint de {}: {}", sessionId.value, it.message) }
    }

    companion object {
        const val PROBE_TIMEOUT_MILLIS = 15_000L
        const val NEIGHBOR_TIMEOUT_MILLIS = 30_000L
        const val MAX_CHAIN_ATTEMPTS = 3
        const val MAX_NEIGHBORS = 100
        const val RAW_EXCERPT_CHARS = 2048
        const val AUDIT_EXCERPT_CHARS = 2048
        private const val VENDOR_SAMPLE_LINES = 64

        private val CISCO_FAMILY = setOf(Vendor.CISCO_IOS, Vendor.CISCO_IOSXE, Vendor.CISCO_NXOS)

        /** (comando, rol que evidencia, regex de evidencia en el output). */
        private val ACTIVE_ROLE_PROBES = listOf(
            Triple("show wlan summary", "wireless_controller", Regex("""(?m)^\s*\d+\s+\S+""")),
            Triple("show spanning-tree summary", "switch", Regex("""(?im)^\s*VLAN0*\d+\b""")),
        )
    }
}

/** Mapeo vendor canónico (net-parsers) → detección de CLI (ai-assistant), inverso de `toNetVendor`. */
internal fun Vendor.toAiVendor(): com.opentermx.ai.context.Vendor = when (this) {
    Vendor.CISCO_IOS -> com.opentermx.ai.context.Vendor.CISCO_IOS
    Vendor.CISCO_IOSXE -> com.opentermx.ai.context.Vendor.CISCO_IOS_XE
    Vendor.CISCO_NXOS -> com.opentermx.ai.context.Vendor.CISCO_NX_OS
    Vendor.ARUBA_AOSCX, Vendor.ARUBA_PROVISION -> com.opentermx.ai.context.Vendor.ARUBA_OS
    Vendor.FORTINET -> com.opentermx.ai.context.Vendor.FORTINET_FORTIOS
    Vendor.HUAWEI_VRP -> com.opentermx.ai.context.Vendor.HUAWEI_VRP
    Vendor.MIKROTIK -> com.opentermx.ai.context.Vendor.MIKROTIK_ROUTEROS
    Vendor.JUNIPER_JUNOS -> com.opentermx.ai.context.Vendor.JUNIPER_JUNOS
    Vendor.GENERIC, Vendor.UNKNOWN -> com.opentermx.ai.context.Vendor.UNKNOWN
}
