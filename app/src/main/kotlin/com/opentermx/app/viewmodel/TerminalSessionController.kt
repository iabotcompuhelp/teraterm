package com.opentermx.app.viewmodel

import com.opentermx.app.ui.terminal.TerminalView
import com.opentermx.common.connection.Connection
import com.opentermx.common.connection.ConnectionState
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

        EventBus.publish(SessionEvent.Opened(session.id.value, session.name))
        connect()
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
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        EventBus.publish(SessionEvent.Closed(session.id.value))
    }
}