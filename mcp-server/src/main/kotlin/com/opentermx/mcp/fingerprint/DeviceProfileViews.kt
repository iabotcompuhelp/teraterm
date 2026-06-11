package com.opentermx.mcp.fingerprint

import com.opentermx.mcp.security.ReadOnlyCommandValidator
import com.opentermx.mcp.telemetry.TelemetryStore
import com.opentermx.netparsers.ParserRegistry
import com.opentermx.netparsers.Vendor
import com.opentermx.telemetrydb.ProfileRepository
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Construye las vistas JSON del perfil de dispositivo (Fase 5C). Una sola implementación
 * la comparten `get_device_profile`, el enriquecimiento de `list_sessions` y el resource
 * `opentermx://devices/{hostname}/profile` — mismo perfil, mismo JSON.
 *
 * Anti-inyección (error #44): los campos que provienen del equipo o la red se declaran
 * en `untrustedFields`; el `summary` es plantilla DETERMINÍSTICA (error #46), truncado a
 * 120 chars y sin saltos de línea ni backticks.
 */
class DeviceProfileViews(
    private val store: TelemetryStore,
    private val validator: ReadOnlyCommandValidator = ReadOnlyCommandValidator.default(),
) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class Enrichment(
        val deviceRole: String,
        val model: String?,
        val criticality: String,
        val summary: String,
    )

    /** Caché TTL del enriquecimiento por host: `list_sessions` no golpea Postgres por llamada. */
    private val enrichmentCache = ConcurrentHashMap<String, Pair<Long, Enrichment?>>()

    /** Resuelve por hostname, por IP de management, o por hostname sin dominio (CDP/FQDN). */
    fun resolveDeviceId(hostname: String): Long? {
        val db = store.db() ?: return null
        return db.devices.findIdByHostname(hostname)
            ?: db.devices.findIdByMgmtAddress(hostname)
            ?: hostname.takeIf { it.contains('.') && !it.first().isDigit() }
                ?.let { db.devices.findIdByHostname(it.substringBefore('.')) }
    }

    /**
     * Enriquecimiento liviano para `list_sessions`. Null si no hay BD, no hay device o
     * no hay perfil — los campos opcionales simplemente no aparecen (error #47).
     */
    fun enrichmentFor(host: String?): Enrichment? {
        if (host.isNullOrBlank()) return null
        val now = System.currentTimeMillis()
        enrichmentCache[host]?.let { (at, cached) ->
            if (now - at < ENRICHMENT_TTL_MILLIS) return cached
        }
        val computed = runCatching { computeEnrichment(host) }
            .onFailure { log.debug("enrichment de `{}` falló: {}", host, it.message) }
            .getOrNull()
        enrichmentCache[host] = now to computed
        return computed
    }

    /** Invalidación por evento (tras refresh_device_fingerprint o edición del operador). */
    fun invalidate(host: String?) {
        if (host != null) enrichmentCache.remove(host)
    }

    private fun computeEnrichment(host: String): Enrichment? {
        val db = store.db() ?: return null
        val deviceId = resolveDeviceId(host) ?: return null
        val device = db.devices.findById(deviceId) ?: return null
        val record = when (val loaded = db.profiles.load(deviceId)) {
            is ProfileRepository.LoadResult.Loaded -> loaded.record
            ProfileRepository.LoadResult.Missing -> return null
        }
        return Enrichment(
            deviceRole = normalizeRole(record.role),
            model = device["model"] as? String,
            criticality = record.criticality,
            summary = summaryOf(device, record),
        )
    }

    /**
     * Perfil completo con el shape del `outputSchema` de `get_device_profile`.
     * [include] vacío = todas las secciones (error #45: selectivo para no saturar contexto).
     */
    fun profileJson(deviceId: Long, include: Set<String> = emptySet()): Map<String, Any?> {
        val db = store.db() ?: return mapOf("found" to false)
        val device = db.devices.findById(deviceId) ?: return mapOf("found" to false)
        val record = when (val loaded = db.profiles.load(deviceId)) {
            is ProfileRepository.LoadResult.Loaded -> loaded.record
            ProfileRepository.LoadResult.Missing -> null
        }
        fun wants(section: String) = include.isEmpty() || section in include

        val vendor = vendorOf(device)
        val out = linkedMapOf<String, Any?>("found" to true)
        val untrusted = mutableListOf<String>()

        if (wants("identity")) {
            out["identity"] = linkedMapOf(
                "hostname" to (device["hostname"] as? String).orEmpty(),
                "role" to normalizeRole(record?.role ?: device["role"] as? String),
                "roleSource" to (record?.roleSource ?: "INFERRED"),
                "vendor" to vendor.name,
                "model" to device["model"],
                "osVersion" to device["os_version"],
                "criticality" to (record?.criticality ?: "medium"),
                "lastFingerprintAt" to record?.lastFingerprintAt,
                "confidence" to (record?.lastConfidence ?: "LOW"),
            )
            untrusted += "identity.hostname"
        }
        if (wants("capabilities")) {
            val caps = record?.profile?.get("capabilities") as? Map<*, *>
            out["capabilities"] = linkedMapOf(
                "supportedTools" to supportedTools(vendor),
                "readonlyProfile" to (caps?.get("readonlyProfile") as? String
                    ?: vendor.name.lowercase() + "-default"),
                "forbiddenCommands" to (caps?.get("forbidden") as? List<*> ?: emptyList<String>()),
            )
        }
        if (wants("allowedCommands")) {
            out["allowedCommands"] = validator.patternsFor(vendor.toAiVendor())
        }
        if (wants("neighbors")) {
            val all = db.neighbors.list(deviceId)
            out["neighbors"] = all.take(MAX_NEIGHBORS).map { n ->
                linkedMapOf(
                    "localInterface" to n["local_interface"],
                    "remoteHostname" to n["remote_hostname"],
                    "remotePort" to n["remote_port"],
                    "knownDevice" to (n["known_device"] == true),
                )
            }
            out["neighborsTruncated"] = all.size > MAX_NEIGHBORS
            untrusted += "neighbors[].remoteHostname"
            untrusted += "neighbors[].remotePort"
        }
        if (wants("operationalNotes")) {
            out["operationalNotes"] = linkedMapOf(
                "notes" to record?.profile?.get("notes"),
                "maintenanceWindow" to record?.profile?.get("maintenanceWindow"),
                "contact" to record?.profile?.get("contact"),
                "uplinks" to (record?.profile?.get("uplinks") as? List<*> ?: emptyList<String>()),
            )
        }
        out["untrustedFields"] = untrusted
        return out
    }

    /**
     * Plantilla determinística del summary (error #46): mismo perfil => mismo texto.
     * Sanitizado (error #44): sin saltos de línea ni backticks, truncado a 120.
     */
    fun summaryOf(device: Map<String, Any?>, record: ProfileRepository.Record): String {
        val role = normalizeRole(record.role)
        val parts = listOfNotNull(
            ROLE_LABELS_ES[role] ?: "Dispositivo",
            (device["site"] as? String)?.ifBlank { null },
            (device["model"] as? String)?.ifBlank { null },
            (record.profile["notes"] as? String)?.ifBlank { null }?.take(NOTE_SNIPPET_CHARS),
            "criticidad ${record.criticality}",
        )
        return sanitizeSummary(parts.joinToString(" — "))
    }

    /** Tools de OpenTermX que aplican a este vendor — derivación determinística. */
    fun supportedTools(vendor: Vendor): List<String> = buildList {
        add("get_device_profile")
        add("refresh_device_fingerprint")
        add("get_device_history")
        if (validator.patternsFor(vendor.toAiVendor()).isNotEmpty()) add("run_readonly_command")
        if (ParserRegistry.interfaceStatsCommand(vendor) != null) {
            add("get_interface_stats")
            add("get_link_status")
            add("get_bandwidth_utilization")
        }
    }

    private fun vendorOf(device: Map<String, Any?>): Vendor =
        runCatching { Vendor.valueOf(device["vendor"].toString()) }.getOrDefault(Vendor.UNKNOWN)

    companion object {
        const val ENRICHMENT_TTL_MILLIS = 60_000L
        const val MAX_NEIGHBORS = 100
        const val SUMMARY_MAX_CHARS = 120
        const val NOTE_SNIPPET_CHARS = 40

        val ROLE_LABELS_ES = mapOf(
            "switch" to "Switch",
            "router" to "Router",
            "firewall" to "Firewall",
            "access_point" to "Access point",
            "wireless_controller" to "Controladora wireless",
            "server" to "Servidor",
            "unknown" to "Dispositivo",
        )

        private val ROLE_ENUM = ROLE_LABELS_ES.keys

        /**
         * Normaliza el rol libre de `devices.role` (Fase 3 usaba 'core-switch', 'ap',
         * 'wlc'...) al enum publicado en el schema de `get_device_profile`.
         */
        fun normalizeRole(raw: String?): String {
            val value = raw?.trim()?.lowercase() ?: return "unknown"
            if (value in ROLE_ENUM) return value
            return when {
                "wlc" == value || "wireless" in value -> "wireless_controller"
                "ap" == value || "access" in value && "point" in value -> "access_point"
                "switch" in value -> "switch"
                "router" in value -> "router"
                "firewall" in value || "fw" == value -> "firewall"
                "server" in value || "srv" == value -> "server"
                else -> "unknown"
            }
        }

        /** Sin saltos de línea ni backticks, espacios colapsados, máx 120 (error #44). */
        fun sanitizeSummary(raw: String): String = raw
            .replace(Regex("""[`\r\n\t]"""), " ")
            .replace(Regex(" {2,}"), " ")
            .trim()
            .take(SUMMARY_MAX_CHARS)
    }
}
