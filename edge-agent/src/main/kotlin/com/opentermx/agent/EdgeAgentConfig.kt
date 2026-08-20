package com.opentermx.agent

import java.net.URI

data class EdgeAgentConfig(
    val gatewayUri: URI,
    val token: String,
    val agentId: String,
    val displayName: String,
    val heartbeatSeconds: Long = 5,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): EdgeAgentConfig? {
            val url = env["OPENTERMX_CONTROL_PLANE_URL"]?.trim().takeUnless { it.isNullOrEmpty() }
                ?: return null
            val token = requireNotNull(env["OPENTERMX_AGENT_TOKEN"]?.takeIf { it.isNotBlank() }) {
                "OPENTERMX_AGENT_TOKEN es obligatorio al configurar OPENTERMX_CONTROL_PLANE_URL"
            }
            val machine = env["COMPUTERNAME"] ?: env["HOSTNAME"] ?: "opentermx-edge"
            val agentId = (env["OPENTERMX_AGENT_ID"] ?: machine).trim()
            require(agentId.matches(Regex("[A-Za-z0-9._-]{1,64}"))) {
                "OPENTERMX_AGENT_ID solo admite letras, números, punto, guion y guion bajo"
            }
            val interval = env["OPENTERMX_AGENT_HEARTBEAT_SECONDS"]?.toLongOrNull() ?: 5L
            require(interval in 2..60) { "OPENTERMX_AGENT_HEARTBEAT_SECONDS debe estar entre 2 y 60" }
            val base = URI.create(url.trimEnd('/'))
            require(base.scheme == "https" || base.scheme == "http") { "La URL del control plane debe ser HTTP(S)" }
            return EdgeAgentConfig(
                gatewayUri = base.resolve("/agent/v1/heartbeat"),
                token = token,
                agentId = agentId,
                displayName = env["OPENTERMX_AGENT_NAME"]?.takeIf { it.isNotBlank() } ?: machine,
                heartbeatSeconds = interval,
            )
        }
    }
}
