package com.opentermx.server

import java.nio.file.Path
import kotlin.io.path.readText

data class ServerDatabaseConfig(
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
    val required: Boolean,
)

data class ServerConfig(
    val bindAddress: String,
    val port: Int,
    val dataDir: Path,
    val token: String?,
    val agentPort: Int = 8766,
    val agentToken: String? = null,
    val readOnly: Boolean = true,
    val database: ServerDatabaseConfig? = null,
    val agentCredentialsFile: Path? = null,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig {
            val bind = env["OPENTERMX_BIND"]?.trim().takeUnless { it.isNullOrEmpty() } ?: "127.0.0.1"
            val port = env["OPENTERMX_PORT"]?.trim()?.toIntOrNull() ?: 8765
            require(port in 1..65535) { "OPENTERMX_PORT debe estar entre 1 y 65535" }

            val dataDir = env["OPENTERMX_DATA_DIR"]?.trim().takeUnless { it.isNullOrEmpty() }
                ?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".opentermx")
            val token = secret(env, "OPENTERMX_MCP_TOKEN")
            val agentPort = env["OPENTERMX_AGENT_PORT"]?.trim()?.toIntOrNull() ?: 8766
            require(agentPort in 1..65535) { "OPENTERMX_AGENT_PORT debe estar entre 1 y 65535" }
            require(agentPort != port) { "Los puertos MCP y de agentes deben ser distintos" }
            val agentToken = secret(env, "OPENTERMX_AGENT_TOKEN")
            val agentCredentialsFile = env["OPENTERMX_AGENT_CREDENTIALS_FILE"]?.trim()
                ?.takeIf { it.isNotEmpty() }?.let(Path::of)?.toAbsolutePath()?.normalize()
            require(agentToken == null || agentCredentialsFile == null) {
                "Configure OPENTERMX_AGENT_TOKEN o OPENTERMX_AGENT_CREDENTIALS_FILE, no ambos"
            }
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
            require(readOnly || agentToken != null || agentCredentialsFile != null) {
                "La autenticación de agentes es obligatoria para desactivar el modo read-only"
            }
            val database = databaseConfig(env)
            return ServerConfig(
                bind, port, dataDir.toAbsolutePath().normalize(), token, agentPort, agentToken, readOnly, database,
                agentCredentialsFile,
            )
        }

        private fun databaseConfig(env: Map<String, String>): ServerDatabaseConfig? {
            val host = env["OPENTERMX_DB_HOST"]?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
            val port = env["OPENTERMX_DB_PORT"]?.trim()?.toIntOrNull() ?: 5432
            require(port in 1..65535) { "OPENTERMX_DB_PORT debe estar entre 1 y 65535" }
            val database = env["OPENTERMX_DB_NAME"]?.trim().takeUnless { it.isNullOrEmpty() } ?: "opentermx"
            val username = env["OPENTERMX_DB_USER"]?.trim().takeUnless { it.isNullOrEmpty() } ?: "opentermx"
            val password = secret(env, "OPENTERMX_DB_PASSWORD")
            require(!password.isNullOrEmpty()) {
                "OPENTERMX_DB_PASSWORD_FILE es obligatorio cuando OPENTERMX_DB_HOST está configurado"
            }
            val required = parseBoolean(env["OPENTERMX_DB_REQUIRED"], "OPENTERMX_DB_REQUIRED", true)
            return ServerDatabaseConfig(host, port, database, username, password, required)
        }

        private fun secret(env: Map<String, String>, name: String): String? {
            val file = env["${name}_FILE"]?.trim().takeUnless { it.isNullOrEmpty() }
            if (file != null) {
                val value = runCatching { Path.of(file).readText().trimEnd('\r', '\n') }
                    .getOrElse { throw IllegalArgumentException("No se pudo leer ${name}_FILE: ${it.message}") }
                require(value.isNotEmpty()) { "${name}_FILE está vacío" }
                return value
            }
            return env[name]?.takeIf { it.isNotBlank() }
        }

        private fun parseBoolean(value: String?, name: String, default: Boolean): Boolean =
            when (value?.trim()?.lowercase()) {
                null, "" -> default
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("$name debe ser true o false")
            }

        private fun isLoopback(address: String): Boolean = when (address.lowercase()) {
            "localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1" -> true
            else -> runCatching { java.net.InetAddress.getByName(address).isLoopbackAddress }
                .getOrDefault(false)
        }
    }
}
