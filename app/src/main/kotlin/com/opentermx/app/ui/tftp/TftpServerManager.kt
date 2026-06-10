package com.opentermx.app.ui.tftp

import com.opentermx.tftp.server.TftpCsvLogger
import com.opentermx.tftp.server.TftpServer
import com.opentermx.tftp.server.TftpServerConfig
import com.opentermx.tftp.server.TftpServerEvent
import com.opentermx.tftp.server.TftpServerListener
import javafx.application.Platform
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.property.ReadOnlyIntegerProperty
import javafx.beans.property.ReadOnlyIntegerWrapper
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide owner of the embedded TFTP server. Decouples the server's lifecycle from
 * `TftpServerDialog`: closing the dialog hides the window but the server keeps serving until
 * the user explicitly stops it (or the app shuts down). The status bar reflects state via
 * {@link #runningProperty} and {@link #portProperty}; the dialog re-attaches by replaying
 * {@link #history()} and registering as a listener.
 */
object TftpServerManager {

    private val log = LoggerFactory.getLogger(javaClass)
    private const val HISTORY_MAX = 1000

    /**
     * Estado compuesto del server corriendo, inmutable y publicado vía UN solo campo
     * `@Volatile`. Antes eran 4 volátiles independientes: los escritores estaban bajo
     * lock pero un lector podía observar mezcla de estados a mitad de un start/stop
     * (p.ej. `server` nuevo con `csvPath` viejo). Con el holder, cada lectura ve un
     * conjunto coherente; para leer varias propiedades juntas usar [snapshot].
     */
    private data class RunningState(
        val server: TftpServer,
        val csvLogger: TftpCsvLogger?,
        val config: TftpServerConfig,
        val csvPath: String,
    )

    @Volatile private var state: RunningState? = null

    private val historyBuf: ArrayDeque<TftpServerEvent> = ArrayDeque()
    private val externalListeners = CopyOnWriteArrayList<TftpServerListener>()

    private val runningWrapper = ReadOnlyBooleanWrapper(false)
    private val portWrapper = ReadOnlyIntegerWrapper(-1)

    val runningProperty: ReadOnlyBooleanProperty get() = runningWrapper.readOnlyProperty
    val portProperty: ReadOnlyIntegerProperty get() = portWrapper.readOnlyProperty

    val isRunning: Boolean get() = state?.server?.isRunning == true
    val actualPort: Int get() = state?.server?.actualPort() ?: -1
    val config: TftpServerConfig? get() = state?.config
    val csvPath: String get() = state?.csvPath ?: ""

    /** Vista atómica del estado para consumidores que leen varias propiedades juntas. */
    data class Snapshot(
        val running: Boolean,
        val port: Int,
        val config: TftpServerConfig?,
        val csvPath: String,
    )

    fun snapshot(): Snapshot {
        val st = state ?: return Snapshot(running = false, port = -1, config = null, csvPath = "")
        return Snapshot(
            running = st.server.isRunning,
            port = st.server.actualPort(),
            config = st.config,
            csvPath = st.csvPath,
        )
    }

    @Synchronized
    fun start(config: TftpServerConfig, csvPath: String): Result<Int> {
        if (isRunning) return Result.success(actualPort)

        val srv = TftpServer(config)
        srv.addListener { event ->
            synchronized(historyBuf) {
                historyBuf.addLast(event)
                while (historyBuf.size > HISTORY_MAX) historyBuf.removeFirst()
            }
            externalListeners.forEach {
                runCatching { it.onEvent(event) }.onFailure { ex -> log.warn("TFTP listener threw", ex) }
            }
        }

        val csv: TftpCsvLogger? = if (csvPath.isBlank()) null else runCatching {
            TftpCsvLogger(Path.of(csvPath))
        }.onFailure { log.warn("Could not open TFTP CSV {}", csvPath, it) }.getOrNull()
        if (csv != null) srv.addListener(csv)

        return runCatching {
            srv.start()
            state = RunningState(server = srv, csvLogger = csv, config = config, csvPath = csvPath)
            val port = srv.actualPort()
            updateProps(running = true, port = port)
            port
        }.onFailure {
            csv?.let { runCatching { it.close() } }
        }
    }

    @Synchronized
    fun stop() {
        val st = state ?: return
        st.server.stop()
        state = null
        st.csvLogger?.let { runCatching { it.close() } }
        synchronized(historyBuf) { historyBuf.clear() }
        updateProps(running = false, port = -1)
    }

    fun addEventListener(listener: TftpServerListener) {
        externalListeners.addIfAbsent(listener)
    }

    fun removeEventListener(listener: TftpServerListener) {
        externalListeners.remove(listener)
    }

    fun history(): List<TftpServerEvent> = synchronized(historyBuf) { historyBuf.toList() }

    private fun updateProps(running: Boolean, port: Int) {
        if (Platform.isFxApplicationThread()) {
            runningWrapper.value = running
            portWrapper.value = port
        } else {
            Platform.runLater {
                runningWrapper.value = running
                portWrapper.value = port
            }
        }
    }
}