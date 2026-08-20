package com.opentermx.app.settings

import com.opentermx.agent.EdgeAgentConfig
import com.opentermx.common.crypto.EncryptedValue
import com.opentermx.common.crypto.SecretCipher
import java.net.URI
import java.nio.file.Path

data class EdgeAgentSettings(
    /** null conserva compatibilidad: usa variables de entorno; true/false es decisión explícita de UI. */
    val enabled: Boolean? = null,
    val controlPlaneUrl: String = "",
    val agentId: String = "",
    val displayName: String = "",
    val heartbeatSeconds: Long = 5,
    val stateDirectory: String = "",
    val token: EncryptedValue? = null,
) {
    fun runtimeConfig(env: Map<String, String> = System.getenv()): EdgeAgentConfig? {
        if (enabled == null) return EdgeAgentConfig.fromEnvironment(env)
        if (enabled == false) return null
        require(controlPlaneUrl.isNotBlank()) { "La URL del control plane es obligatoria" }
        val plainToken = token?.let { SecretCipher.decrypt(it) }.orEmpty()
        require(plainToken.toByteArray().size >= 16) { "El token debe tener al menos 16 bytes" }
        val machine = env["COMPUTERNAME"] ?: env["HOSTNAME"] ?: "opentermx-edge"
        val resolvedId = agentId.ifBlank { machine }
        require(resolvedId.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Identificador de agente inválido" }
        require(heartbeatSeconds in 2..60) { "El heartbeat debe estar entre 2 y 60 segundos" }
        val base = URI.create(controlPlaneUrl.trim().trimEnd('/'))
        require(base.scheme == "http" || base.scheme == "https") { "La URL debe usar HTTP o HTTPS" }
        return EdgeAgentConfig(
            gatewayUri = base.resolve("/agent/v1/heartbeat"),
            token = plainToken,
            agentId = resolvedId,
            displayName = displayName.ifBlank { machine },
            heartbeatSeconds = heartbeatSeconds,
            stateDir = stateDirectory.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".opentermx", "agent"),
        )
    }
}
