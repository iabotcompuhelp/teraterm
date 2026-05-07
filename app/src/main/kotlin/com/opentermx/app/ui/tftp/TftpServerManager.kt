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

    @Volatile private var server: TftpServer? = null
    @Volatile private var csvLogger: TftpCsvLogger? = null
    @Volatile private var currentConfig: TftpServerConfig? = null
    @Volatile private var currentCsvPath: String = ""

    private val historyBuf: ArrayDeque<TftpServerEvent> = ArrayDeque()
    private val externalListeners = CopyOnWriteArrayList<TftpServerListener>()

    private val runningWrapper = ReadOnlyBooleanWrapper(false)
    private val portWrapper = ReadOnlyIntegerWrapper(-1)

    val runningProperty: ReadOnlyBooleanProperty get() = runningWrapper.readOnlyProperty
    val portProperty: ReadOnlyIntegerProperty get() = portWrapper.readOnlyProperty

    val isRunning: Boolean get() = server?.isRunning == true
    val actualPort: Int get() = server?.actualPort() ?: -1
    val config: TftpServerConfig? get() = currentConfig
    val csvPath: String get() = currentCsvPath

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
            server = srv
            csvLogger = csv
            currentConfig = config
            currentCsvPath = csvPath
            val port = srv.actualPort()
            updateProps(running = true, port = port)
            port
        }.onFailure {
            csv?.let { runCatching { it.close() } }
        }
    }

    @Synchronized
    fun stop() {
        val srv = server ?: return
        srv.stop()
        server = null
        csvLogger?.let { runCatching { it.close() } }
        csvLogger = null
        currentConfig = null
        currentCsvPath = ""
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