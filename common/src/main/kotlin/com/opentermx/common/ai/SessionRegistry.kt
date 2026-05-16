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
    private val connections = ConcurrentHashMap<SessionId, com.opentermx.common.connection.Connection>()
    private val metadata = ConcurrentHashMap<SessionId, SessionMetadata>()
    private val changeListeners = java.util.concurrent.CopyOnWriteArrayList<(SessionChange) -> Unit>()

    @JvmStatic
    @JvmOverloads
    fun register(
        id: SessionId,
        metadata: SessionMetadata,
        provider: TerminalBufferProvider,
        sink: CommandSink? = null,
        connection: com.opentermx.common.connection.Connection? = null,
    ) {
        this.metadata[id] = metadata
        providers[id] = provider
        if (sink != null) sinks[id] = sink else sinks.remove(id)
        if (connection != null) connections[id] = connection else connections.remove(id)
        notify(SessionChange.Opened(id, metadata))
    }

    @JvmStatic
    fun unregister(id: SessionId) {
        val meta = metadata.remove(id)
        providers.remove(id)
        sinks.remove(id)
        connections.remove(id)
        if (meta != null) notify(SessionChange.Closed(id, meta))
    }

    @JvmStatic
    fun bufferProvider(id: SessionId): TerminalBufferProvider? = providers[id]

    @JvmStatic
    fun sinkOf(id: SessionId): CommandSink? = sinks[id]

    @JvmStatic
    fun connectionOf(id: SessionId): com.opentermx.common.connection.Connection? = connections[id]

    @JvmStatic
    fun metadataOf(id: SessionId): SessionMetadata? = metadata[id]

    @JvmStatic
    fun activeSessions(): List<SessionDescriptor> =
        metadata.entries.map { (id, meta) -> SessionDescriptor(id, meta) }

    @JvmStatic
    fun lastLinesOf(id: SessionId, count: Int): List<String> =
        providers[id]?.lastLines(count) ?: emptyList()

    /**
     * Suscribe a cambios de estado del registro (sesiones abiertas/cerradas). Devuelve un
     * `AutoCloseable` que des-suscribe al cerrarse. Pensado para que el servidor MCP
     * pueda emitir `notifications/sessions/changed` (T11 de phase 2).
     */
    @JvmStatic
    fun addChangeListener(listener: (SessionChange) -> Unit): AutoCloseable {
        changeListeners += listener
        return AutoCloseable { changeListeners -= listener }
    }

    private fun notify(change: SessionChange) {
        changeListeners.forEach { runCatching { it(change) } }
    }
}

/** Evento del [SessionRegistry] — alimenta las notifications SSE. */
sealed interface SessionChange {
    val id: SessionId
    val metadata: SessionMetadata

    data class Opened(override val id: SessionId, override val metadata: SessionMetadata) : SessionChange
    data class Closed(override val id: SessionId, override val metadata: SessionMetadata) : SessionChange
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
