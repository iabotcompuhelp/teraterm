package com.opentermx.server.agent

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties

interface AgentCredentials {
    fun authenticate(agentId: String, suppliedToken: String): Boolean
    fun signingSecret(agentId: String): ByteArray?
}

class SharedAgentCredentials(private val token: String) : AgentCredentials {
    override fun authenticate(agentId: String, suppliedToken: String): Boolean = secureEquals(token, suppliedToken)
    override fun signingSecret(agentId: String): ByteArray = token.toByteArray(Charsets.UTF_8)
}

/**
 * Archivo Properties montado como secreto: `agentId=token`. Se relee cuando cambia para permitir
 * altas, rotación y revocación sin reiniciar el plano de control.
 */
class FileAgentCredentials(
    private val path: Path,
    private val onLifecycle: (String, String) -> Unit = { _, _ -> },
) : AgentCredentials {
    @Volatile private var snapshot = Snapshot(-1, emptyMap())

    init { require(Files.isRegularFile(path)) { "Archivo de credenciales de agentes no encontrado" } }

    override fun authenticate(agentId: String, suppliedToken: String): Boolean {
        val expected = credentials()[agentId] ?: return false
        return secureEquals(expected, suppliedToken)
    }

    override fun signingSecret(agentId: String): ByteArray? =
        credentials()[agentId]?.toByteArray(Charsets.UTF_8)

    @Synchronized
    private fun credentials(): Map<String, String> {
        val modified = Files.getLastModifiedTime(path).toMillis()
        if (snapshot.modified == modified) return snapshot.values
        val properties = Properties().also { Files.newInputStream(path).use(it::load) }
        val values = properties.stringPropertyNames().associateWith { id ->
            require(id.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "agentId inválido en credenciales" }
            properties.getProperty(id).trim().also {
                require(it.toByteArray().size >= 16) { "Token de $id debe tener al menos 16 bytes" }
            }
        }
        require(values.isNotEmpty()) { "El archivo de credenciales de agentes está vacío" }
        val previous = snapshot.values
        snapshot = Snapshot(modified, values)
        values.keys.minus(previous.keys).forEach { onLifecycle(it, "ENROLLED") }
        previous.keys.minus(values.keys).forEach { onLifecycle(it, "REVOKED") }
        values.keys.intersect(previous.keys).filter { values[it] != previous[it] }
            .forEach { onLifecycle(it, "ROTATED") }
        return values
    }

    private data class Snapshot(val modified: Long, val values: Map<String, String>)
}

private fun secureEquals(expected: String, supplied: String): Boolean = MessageDigest.isEqual(
    expected.toByteArray(Charsets.UTF_8), supplied.toByteArray(Charsets.UTF_8),
)
