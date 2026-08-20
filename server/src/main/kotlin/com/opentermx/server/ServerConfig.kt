package com.opentermx.server

import java.nio.file.Path

data class ServerConfig(
    val bindAddress: String,
    val port: Int,
    val dataDir: Path,
    val token: String?,
    val agentPort: Int = 8766,
    val agentToken: String? = null,
    val readOnly: Boolean = true,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig {
            val bind = env["OPENTERMX_BIND"]?.trim().takeUnless { it.isNullOrEmpty() } ?: "127.0.0.1"
            val port = env["OPENTERMX_PORT"]?.trim()?.toIntOrNull() ?: 8765
            require(port in 1..65535) { "OPENTERMX_PORT debe estar entre 1 y 65535" }

            val dataDir = env["OPENTERMX_DATA_DIR"]?.trim().takeUnless { it.isNullOrEmpty() }
                ?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".opentermx")
            val token = env["OPENTERMX_MCP_TOKEN"]?.takeIf { it.isNotBlank() }
            val agentPort = env["OPENTERMX_AGENT_PORT"]?.trim()?.toIntOrNull() ?: 8766
            require(agentPort in 1..65535) { "OPENTERMX_AGENT_PORT debe estar entre 1 y 65535" }
            require(agentPort != port) { "Los puertos MCP y de agentes deben ser distintos" }
            val agentToken = env["OPENTERMX_AGENT_TOKEN"]?.takeIf { it.isNotBlank() }
            require(agentToken == null || agentToken.toByteArray().size >= 16) {
                "OPENTERMX_AGENT_TOKEN debe tener al menos 16 bytes"
            }

            require(token != null || isLoopback(bind)) {
                "OPENTERMX_MCP_TOKEN es obligatorio cuando OPENTERMX_BIND no es loopback"
            }
            val readOnly = when (env["OPENTERMX_READ_ONLY"]?.trim()?.lowercase()) {
                null, "", "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("OPENTERMX_READ_ONLY debe ser true o false")
            }
            require(readOnly || agentToken != null) {
                "OPENTERMX_AGENT_TOKEN es obligatorio para desactivar el modo read-only"
            }
            return ServerConfig(bind, port, dataDir.toAbsolutePath().normalize(), token, agentPort, agentToken, readOnly)
        }

        private fun isLoopback(address: String): Boolean = when (address.lowercase()) {
            "localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1" -> true
            else -> runCatching { java.net.InetAddress.getByName(address).isLoopbackAddress }
                .getOrDefault(false)
        }
    }
}
