package com.opentermx.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.ai.safety.CredentialRedactor
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.mcp.protocol.ResourceContent
import com.opentermx.mcp.protocol.ResourceDescriptor
import com.opentermx.mcp.protocol.ResourceProvider

/**
 * Implementación concreta del [ResourceProvider] para OpenTermX. Expone:
 *
 *  - `opentermx://audit-log` — el audit log completo redactado.
 *  - `opentermx://sessions` — snapshot de sesiones activas (equivalente a la tool
 *    `list_sessions` pero como resource consumible por clientes MCP que prefieren el
 *    paradigma `resources` sobre `tools`).
 *  - `opentermx://devices/{hostname}/profile` (Fase 5C) — mismo JSON que la tool
 *    `get_device_profile`. Solo con BD disponible y [profileViews] cableado.
 *  - `opentermx://topology/summary` (Fase 5C) — quién conecta con quién, según los
 *    vecinos descubiertos. Hostnames remotos = datos NO confiables.
 *
 * Si el cliente no soporta resources, no pasa nada: las tools cubren lo mismo.
 */
class OpenTermXResources(
    private val auditLog: AiAuditLog = AiAuditLog(),
    private val redactor: CredentialRedactor = CredentialRedactor(),
    private val store: com.opentermx.mcp.telemetry.TelemetryStore? = null,
    private val profileViews: com.opentermx.mcp.fingerprint.DeviceProfileViews? = null,
) : ResourceProvider {

    private val mapper: ObjectMapper = jacksonObjectMapper()

    override fun list(): List<ResourceDescriptor> = buildList {
        add(
            ResourceDescriptor(
                uri = URI_AUDIT_LOG,
                name = "Audit log de IA",
                description = "Entradas históricas del audit log con redacción aplicada.",
            )
        )
        add(
            ResourceDescriptor(
                uri = URI_SESSIONS,
                name = "Sesiones activas",
                description = "Snapshot del SessionRegistry — equivalente a list_sessions.",
            )
        )
        val db = store?.db()
        if (db != null && profileViews != null) {
            add(
                ResourceDescriptor(
                    uri = URI_TOPOLOGY,
                    name = "Resumen de topología",
                    description = "Vecinos LLDP/CDP/MNDP cruzados de toda la flota — " +
                        "equivalente agregado de get_device_profile.neighbors.",
                )
            )
            db.devices.list(limit = MAX_DEVICE_RESOURCES).forEach { device ->
                val hostname = device["hostname"] as? String ?: return@forEach
                add(
                    ResourceDescriptor(
                        uri = deviceProfileUri(hostname),
                        name = "Perfil de $hostname",
                        description = "Perfil del dispositivo — equivalente a get_device_profile.",
                    )
                )
            }
        }
    }

    override fun read(uri: String): ResourceContent? = when (uri) {
        URI_AUDIT_LOG -> {
            val entries = auditLog.read(limit = 200).map { e ->
                linkedMapOf(
                    "timestampMillis" to e.timestampMillis,
                    "sessionId" to e.sessionId,
                    "host" to e.host,
                    "vendor" to e.vendor,
                    "prompt" to redactor.redact(e.prompt),
                    "commands" to redactor.redactLines(e.commands),
                    "executedCount" to e.executedCount,
                    "rejected" to e.rejected,
                    "outputTail" to redactor.redact(e.outputTail),
                )
            }
            ResourceContent(uri, mapper.writeValueAsString(linkedMapOf("entries" to entries)))
        }
        URI_SESSIONS -> {
            val sessions = SessionRegistry.activeSessions().map { d ->
                linkedMapOf(
                    "sessionId" to d.id.value,
                    "name" to d.metadata.name,
                    "protocol" to d.metadata.protocol,
                    "host" to d.metadata.host,
                    "port" to d.metadata.port,
                    "username" to d.metadata.username,
                )
            }
            ResourceContent(uri, mapper.writeValueAsString(linkedMapOf("sessions" to sessions)))
        }
        URI_TOPOLOGY -> {
            val db = store?.db() ?: return null
            val links = db.neighbors.topologySummary().map { row ->
                linkedMapOf(
                    "localHostname" to row["local_hostname"],
                    "localInterface" to row["local_interface"],
                    "remoteHostname" to row["remote_hostname"],
                    "remotePort" to row["remote_port"],
                    "protocol" to row["protocol"],
                    "knownDevice" to (row["known_device"] == true),
                )
            }
            ResourceContent(
                uri,
                mapper.writeValueAsString(
                    linkedMapOf(
                        "links" to links,
                        "untrustedFields" to listOf("links[].remoteHostname", "links[].remotePort"),
                    )
                ),
            )
        }
        else -> readDeviceProfile(uri)
    }

    private fun readDeviceProfile(uri: String): ResourceContent? {
        val views = profileViews ?: return null
        val match = DEVICE_PROFILE_URI.matchEntire(uri) ?: return null
        val hostname = java.net.URLDecoder.decode(match.groupValues[1], Charsets.UTF_8)
        val deviceId = views.resolveDeviceId(hostname) ?: return null
        return ResourceContent(uri, mapper.writeValueAsString(views.profileJson(deviceId)))
    }

    companion object {
        const val URI_AUDIT_LOG = "opentermx://audit-log"
        const val URI_SESSIONS = "opentermx://sessions"
        const val URI_TOPOLOGY = "opentermx://topology/summary"
        const val MAX_DEVICE_RESOURCES = 200

        private val DEVICE_PROFILE_URI = Regex("""^opentermx://devices/([^/]+)/profile$""")

        fun deviceProfileUri(hostname: String): String =
            "opentermx://devices/" + java.net.URLEncoder.encode(hostname, Charsets.UTF_8) + "/profile"
    }
}