package com.opentermx.mcp.handlers

import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.fingerprint.DeviceProfileViews
import com.opentermx.mcp.fingerprint.FingerprintService
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.INVALID_ARGUMENT
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.NOT_FOUND
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.UNAVAILABLE
import com.opentermx.mcp.telemetry.TelemetryStore
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions
import com.opentermx.netparsers.Vendor

/* =====================================================================================
 * Tools MCP de perfiles de dispositivo (Fase 5C): get_device_profile,
 * refresh_device_fingerprint, list_devices y diagnose_device_context.
 * ===================================================================================== */

/** Resuelve deviceId desde sessionId o deviceHostname (al menos uno). */
internal fun resolveDevice(
    store: TelemetryStore,
    views: DeviceProfileViews,
    sessionIdRaw: String?,
    deviceHostname: String?,
): Long? {
    store.db() ?: throw McpToolException(
        UNAVAILABLE,
        "DB_UNAVAILABLE: la base de telemetría no está configurada o no responde. " +
            "Los perfiles de dispositivo viven en PostgreSQL (Fase 5B).",
    )
    val hostname = when {
        !deviceHostname.isNullOrBlank() -> deviceHostname
        !sessionIdRaw.isNullOrBlank() -> {
            val metadata = SessionRegistry.metadataOf(SessionId(sessionIdRaw))
                ?: throw McpToolException(NOT_FOUND, "Sesión `$sessionIdRaw` no encontrada en el registro")
            metadata.host
                ?: throw McpToolException(INVALID_ARGUMENT, "La sesión `$sessionIdRaw` no expone host")
        }
        else -> throw McpToolException(
            INVALID_ARGUMENT, "Indicá `sessionId` o `deviceHostname` (al menos uno).",
        )
    }
    return views.resolveDeviceId(hostname)
}

/** `get_device_profile`: vista completa del perfil con secciones selectivas (error #45). */
class GetDeviceProfileHandler(
    private val store: TelemetryStore,
    private val views: DeviceProfileViews,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.GET_DEVICE_PROFILE

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val sessionId = Args.optionalString(args, "sessionId")
        val deviceHostname = Args.optionalString(args, "deviceHostname")
        val include = (args["include"] as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet()

        val deviceId = resolveDevice(store, views, sessionId, deviceHostname)
            ?: return linkedMapOf("found" to false)
        return views.profileJson(deviceId, include)
    }
}

/**
 * `refresh_device_fingerprint`: corre el [FingerprintService] sobre la sesión y, si el
 * servicio no está en dry-run y hay BD, persiste identidad + vecinos (Fase 5B).
 */
class RefreshDeviceFingerprintHandler(
    private val service: FingerprintService,
    private val store: TelemetryStore,
    views: DeviceProfileViews? = null,
    /** Regenerador de docs RAG (Fase 5D): se dispara tras persistir el perfil. */
    ragDocs: com.opentermx.mcp.fingerprint.RagDocGenerator? = null,
) : ToolHandler {

    private val persister = com.opentermx.mcp.fingerprint.FingerprintPersister(store, views, ragDocs)

    override val definition: ToolDef = ToolDefinitions.REFRESH_DEVICE_FINGERPRINT

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val sessionIdRaw = Args.requireString(args, "sessionId")
        val includeNeighbors = args["includeNeighbors"] as? Boolean ?: true
        val sessionId = SessionId(sessionIdRaw)
        val metadata = SessionRegistry.metadataOf(sessionId)
            ?: throw McpToolException(NOT_FOUND, "Sesión `$sessionIdRaw` no encontrada en el registro")
        SessionRegistry.sinkOf(sessionId)
            ?: throw McpToolException(UNAVAILABLE, "Sesión `$sessionIdRaw` sin sink: no es inyectable")

        val report = service.fingerprint(sessionId, includeNeighbors)
        val outcome = if (service.dryRun) com.opentermx.mcp.fingerprint.FingerprintPersister.Outcome(false)
        else persister.persist(report, metadata, includeNeighbors)

        return linkedMapOf(
            "traceId" to report.traceId,
            "identity" to report.identity.toJson(),
            "roleSuggestion" to report.roleSuggestion,
            "roleMatchedBy" to report.roleMatchedBy,
            "neighbors" to report.neighbors.map { it.toJson() },
            "neighborsTruncated" to report.neighborsTruncated,
            "attempts" to report.attempts.map {
                linkedMapOf(
                    "probeId" to it.probeId,
                    "command" to it.command,
                    "outcome" to it.outcome,
                    "detail" to it.detail,
                )
            },
            "warnings" to report.warnings,
            "persisted" to outcome.persisted,
            "identityChanged" to outcome.identityChanged,
            "dryRun" to report.dryRun,
            "durationMs" to report.durationMs,
        )
    }
}

/** `list_devices`: inventario local con filtros. No requiere sesión activa. */
class ListDevicesHandler(
    private val store: TelemetryStore,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.LIST_DEVICES

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val db = store.db() ?: throw McpToolException(
            UNAVAILABLE,
            "DB_UNAVAILABLE: la base de telemetría no está configurada o no responde.",
        )
        val vendorArg = Args.optionalString(args, "vendor")
        if (vendorArg != null && runCatching { Vendor.valueOf(vendorArg.uppercase()) }.isFailure) {
            throw McpToolException(
                INVALID_ARGUMENT,
                "vendor `$vendorArg` desconocido. Valores: ${Vendor.entries.joinToString(", ")}",
            )
        }
        val rows = db.devices.list(
            role = Args.optionalString(args, "role"),
            site = Args.optionalString(args, "site"),
            vendor = vendorArg?.uppercase(),
            criticality = Args.optionalString(args, "criticality"),
            limit = Args.optionalInt(args, "limit", default = 100, min = 1, max = 500),
        )
        return linkedMapOf(
            "count" to rows.size,
            "devices" to rows.map { d ->
                linkedMapOf(
                    "hostname" to d["hostname"],
                    "mgmtAddress" to d["mgmt_address"],
                    "vendor" to d["vendor"],
                    "model" to d["model"],
                    "osVersion" to d["os_version"],
                    "site" to d["site"],
                    "role" to DeviceProfileViews.normalizeRole(d["role"] as? String),
                    "criticality" to d["criticality"],
                    "roleSource" to d["role_source"],
                    "enabled" to d["enabled"],
                )
            },
        )
    }
}

/**
 * `diagnose_device_context` (read-only): por qué OpenTermX cree lo que cree de un
 * dispositivo — último fingerprint con excerpt, edad del perfil, vecinos, doc RAG.
 */
class DiagnoseDeviceContextHandler(
    private val store: TelemetryStore,
    private val views: DeviceProfileViews,
    /** Estado del doc RAG auto-generado (lo cablea la Fase 5D); null = aún sin generador. */
    private val ragDocStatus: ((hostname: String) -> Map<String, Any?>)? = null,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.DIAGNOSE_DEVICE_CONTEXT

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val hostname = Args.requireString(args, "deviceHostname")
        val db = store.db() ?: throw McpToolException(
            UNAVAILABLE,
            "DB_UNAVAILABLE: la base de telemetría no está configurada o no responde.",
        )
        val deviceId = views.resolveDeviceId(hostname)
            ?: return linkedMapOf("found" to false, "deviceHostname" to hostname)

        val last = db.fingerprints.latest(deviceId)
        val recent = db.fingerprints.listRecent(deviceId, 5).map { row ->
            linkedMapOf(
                "takenAt" to row["taken_at"],
                "probeId" to row["probe_id"],
                "vendor" to row["vendor"]?.toString(),
                "model" to row["model"],
                "osVersion" to row["os_version"],
                "confidence" to row["confidence"]?.toString(),
                "traceId" to row["trace_id"],
            )
        }
        val record = when (val loaded = db.profiles.load(deviceId)) {
            is com.opentermx.telemetrydb.ProfileRepository.LoadResult.Loaded -> loaded.record
            com.opentermx.telemetrydb.ProfileRepository.LoadResult.Missing -> null
        }
        return linkedMapOf(
            "found" to true,
            "deviceHostname" to hostname,
            "lastFingerprint" to last?.let { row ->
                linkedMapOf(
                    "takenAt" to row["taken_at"],
                    "probeId" to row["probe_id"],
                    "vendor" to row["vendor"]?.toString(),
                    "model" to row["model"],
                    "osVersion" to row["os_version"],
                    "confidence" to row["confidence"]?.toString(),
                    "traceId" to row["trace_id"],
                    "rawExcerpt" to row["raw_excerpt"],
                )
            },
            "profileUpdatedAt" to record?.updatedAt,
            "roleSource" to record?.roleSource,
            "neighborsCount" to db.neighbors.list(deviceId).size,
            "recentFingerprints" to recent,
            "ragDoc" to (ragDocStatus?.invoke(hostname)
                ?: linkedMapOf("exists" to false, "path" to null, "sourceHash" to null)),
        )
    }
}
