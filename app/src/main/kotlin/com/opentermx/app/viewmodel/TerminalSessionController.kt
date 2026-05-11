package com.opentermx.app.viewmodel

import com.opentermx.app.ui.terminal.TerminalView
import com.opentermx.common.ai.CommandSink
import com.opentermx.common.ai.SessionMetadata
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.ai.TerminalBufferProvider
import com.opentermx.common.connection.Connection
import com.opentermx.common.connection.ConnectionConfig
import com.opentermx.common.connection.ConnectionState
import com.opentermx.common.connection.SerialConfig
import com.opentermx.common.connection.SshConfig
import com.opentermx.common.connection.TcpRawConfig
import com.opentermx.common.connection.TelnetConfig
import com.opentermx.common.event.ConnectionEvent
import com.opentermx.common.event.EventBus
import com.opentermx.common.event.SessionEvent
import com.opentermx.common.session.Session
import com.opentermx.ssh.SshConnection
import com.opentermx.telnet.TelnetConnection
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class TerminalSessionController(
    val session: Session,
    val terminal: TerminalView,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val connection: Connection = session.connection
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val stateWrapper = ReadOnlyObjectWrapper(ConnectionState.DISCONNECTED)
    val state: ReadOnlyObjectProperty<ConnectionState> get() = stateWrapper.readOnlyProperty

    fun start() {
        connection.setDataHandler { data, length ->
            terminal.appendBytes(data, length)
            EventBus.publish(
                ConnectionEvent.DataReceived(
                    sessionId = session.id.value,
                    data = data.copyOf(length),
                    length = length,
                )
            )
        }
        connection.setStateHandler { newState, error ->
            val previous = stateWrapper.value
            Platform.runLater { stateWrapper.value = newState }
            EventBus.publish(
                ConnectionEvent.StateChanged(
                    sessionId = session.id.value,
                    previous = previous,
                    current = newState,
                    error = error,
                )
            )
            if (error != null) {
                terminal.append("\n[error] ${error.message ?: error.javaClass.simpleName}\n")
            }
        }
        terminal.onInput = { input -> sendInput(input) }
        terminal.onResize = { cols, rows ->
            when (val conn = connection) {
                is SshConnection -> runCatching { conn.resizePty(cols, rows) }
                    .onFailure { log.warn("SSH PTY resize fallido", it) }
                is TelnetConnection -> runCatching { conn.resizePty(cols, rows) }
                    .onFailure { log.warn("Telnet PTY resize fallido", it) }
                else -> {}
            }
        }

        SessionRegistry.register(
            id = session.id,
            metadata = buildMetadata(session.config),
            provider = TerminalBufferProvider { count -> terminal.snapshotLastLines(count) },
            sink = CommandSink { line ->
                if (connection.state != ConnectionState.CONNECTED) return@CommandSink false
                runCatching {
                    val payload = if (line.endsWith("\n")) line else "$line\n"
                    val bytes = payload.toByteArray(Charsets.UTF_8)
                    scope.launch {
                        runCatching { connection.send(bytes) }
                            .onFailure { log.warn("CommandSink envío fallido", it) }
                    }
                    EventBus.publish(
                        ConnectionEvent.DataSent(
                            sessionId = session.id.value,
                            length = bytes.size,
                        )
                    )
                    true
                }.getOrElse { false }
            },
        )
        EventBus.publish(SessionEvent.Opened(session.id.value, session.name))
        connect()
    }

    private fun buildMetadata(config: ConnectionConfig): SessionMetadata = when (config) {
        is SshConfig -> SessionMetadata(session.name, "SSH", config.host, config.port, config.username)
        is TelnetConfig -> SessionMetadata(session.name, "Telnet", config.host, config.port, null)
        is TcpRawConfig -> SessionMetadata(session.name, "TCP/Raw", config.host, config.port, null)
        is SerialConfig -> SessionMetadata(session.name, "Serial", config.portName, null, null)
        else -> SessionMetadata(session.name, config.javaClass.simpleName, null, null, null)
    }

    fun connect() {
        scope.launch {
            try {
                connection.connect()
            } catch (e: Exception) {
                log.error("Conexión fallida", e)
                Platform.runLater {
                    terminal.append("\n[error] Conexión fallida: ${e.message}\n")
                }
            }
        }
    }

    fun disconnect() {
        scope.launch {
            runCatching { connection.disconnect() }
                .onFailure { log.warn("Error al desconectar", it) }
        }
    }

    private fun sendInput(input: String) {
        if (connection.state != ConnectionState.CONNECTED) {
            terminal.append("\n[no conectado]\n")
            return
        }
        scope.launch {
            try {
                val bytes = input.toByteArray(Charsets.UTF_8)
                connection.send(bytes)
                EventBus.publish(
                    ConnectionEvent.DataSent(
                        sessionId = session.id.value,
                        length = bytes.size,
                    )
                )
            } catch (e: Exception) {
                log.warn("Envío fallido", e)
                Platform.runLater {
                    terminal.append("\n[error envío] ${e.message}\n")
                }
            }
        }
    }

    fun stop() {
        runCatching { connection.close() }
            .onFailure { log.warn("Error cerrando conexión", it) }
        SessionRegistry.unregister(session.id)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        EventBus.publish(SessionEvent.Closed(session.id.value))
    }
}