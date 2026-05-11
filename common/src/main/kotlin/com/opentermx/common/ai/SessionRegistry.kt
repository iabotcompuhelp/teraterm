package com.opentermx.common.ai

import com.opentermx.common.session.SessionId
import java.util.concurrent.ConcurrentHashMap

/**
 * Proveedor de buffer de un terminal activo. La implementación concreta
 * (en el módulo `app`) lee las últimas N líneas del `TerminalBuffer`.
 */
fun interface TerminalBufferProvider {
    fun lastLines(count: Int): List<String>
}

/**
 * Sumidero de comandos por sesión. Lo registra `TerminalSessionController` para que
 * el panel de revisión IA pueda inyectar comandos sin acoplarse a `Connection`.
 *
 * - [sendLine] envía la línea seguida del newline adecuado para la sesión. Retorna `true`
 *   si la conexión está CONNECTED y los bytes se entregaron al I/O, `false` en caso contrario
 *   (caller decide cómo reportar).
 */
fun interface CommandSink {
    fun sendLine(line: String): Boolean
}

/**
 * Registro thread-safe de sesiones activas, accesible desde cualquier módulo
 * (ai-assistant, macro-engine) sin depender de `app`.
 *
 * `TerminalSessionController` registra cada sesión al iniciarla y la deregistra al cerrarla.
 */
object SessionRegistry {

    private val providers = ConcurrentHashMap<SessionId, TerminalBufferProvider>()
    private val sinks = ConcurrentHashMap<SessionId, CommandSink>()
    private val metadata = ConcurrentHashMap<SessionId, SessionMetadata>()

    @JvmStatic
    @JvmOverloads
    fun register(
        id: SessionId,
        metadata: SessionMetadata,
        provider: TerminalBufferProvider,
        sink: CommandSink? = null,
    ) {
        this.metadata[id] = metadata
        providers[id] = provider
        if (sink != null) sinks[id] = sink else sinks.remove(id)
    }

    @JvmStatic
    fun unregister(id: SessionId) {
        providers.remove(id)
        sinks.remove(id)
        metadata.remove(id)
    }

    @JvmStatic
    fun bufferProvider(id: SessionId): TerminalBufferProvider? = providers[id]

    @JvmStatic
    fun sinkOf(id: SessionId): CommandSink? = sinks[id]

    @JvmStatic
    fun metadataOf(id: SessionId): SessionMetadata? = metadata[id]

    @JvmStatic
    fun activeSessions(): List<SessionDescriptor> =
        metadata.entries.map { (id, meta) -> SessionDescriptor(id, meta) }

    @JvmStatic
    fun lastLinesOf(id: SessionId, count: Int): List<String> =
        providers[id]?.lastLines(count) ?: emptyList()
}

data class SessionMetadata(
    val name: String,
    val protocol: String,
    val host: String?,
    val port: Int?,
    val username: String?,
)

data class SessionDescriptor(
    val id: SessionId,
    val metadata: SessionMetadata,
)
