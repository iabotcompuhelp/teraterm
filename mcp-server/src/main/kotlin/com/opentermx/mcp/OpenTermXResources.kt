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
 * Implementación concreta del [ResourceProvider] para OpenTermX. Expone dos URIs:
 *
 *  - `opentermx://audit-log` — el audit log completo redactado.
 *  - `opentermx://sessions` — snapshot de sesiones activas (equivalente a la tool
 *    `list_sessions` pero como resource consumible por clientes MCP que prefieren el
 *    paradigma `resources` sobre `tools`).
 */
class OpenTermXResources(
    private val auditLog: AiAuditLog = AiAuditLog(),
    private val redactor: CredentialRedactor = CredentialRedactor(),
) : ResourceProvider {

    private val mapper: ObjectMapper = jacksonObjectMapper()

    override fun list(): List<ResourceDescriptor> = listOf(
        ResourceDescriptor(
            uri = URI_AUDIT_LOG,
            name = "Audit log de IA",
            description = "Entradas históricas del audit log con redacción aplicada.",
        ),
        ResourceDescriptor(
            uri = URI_SESSIONS,
            name = "Sesiones activas",
            description = "Snapshot del SessionRegistry — equivalente a list_sessions.",
        ),
    )

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
        else -> null
    }

    companion object {
        const val URI_AUDIT_LOG = "opentermx://audit-log"
        const val URI_SESSIONS = "opentermx://sessions"
    }
}