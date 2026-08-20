package com.opentermx.server

import java.nio.file.Path

data class ServerConfig(
    val bindAddress: String,
    val port: Int,
    val dataDir: Path,
    val token: String?,
    val agentPort: Int = 8766,
    val agentToken: String? = null,
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

            require(token != null || isLoopback(bind)) {
                "OPENTERMX_MCP_TOKEN es obligatorio cuando OPENTERMX_BIND no es loopback"
            }
            return ServerConfig(bind, port, dataDir.toAbsolutePath().normalize(), token, agentPort, agentToken)
        }

        private fun isLoopback(address: String): Boolean = when (address.lowercase()) {
            "localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1" -> true
            else -> runCatching { java.net.InetAddress.getByName(address).isLoopbackAddress }
                .getOrDefault(false)
        }
    }
}
