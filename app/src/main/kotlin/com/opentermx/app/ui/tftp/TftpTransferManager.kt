package com.opentermx.app.ui.tftp

import com.opentermx.tftp.client.TftpClient
import com.opentermx.tftp.client.TftpClientOptions
import com.opentermx.tftp.server.TftpCsvLogger
import javafx.application.Platform
import javafx.beans.property.ReadOnlyDoubleProperty
import javafx.beans.property.ReadOnlyDoubleWrapper
import javafx.beans.property.ReadOnlyIntegerProperty
import javafx.beans.property.ReadOnlyIntegerWrapper
import javafx.beans.property.ReadOnlyLongProperty
import javafx.beans.property.ReadOnlyLongWrapper
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.ReadOnlyStringProperty
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

enum class TftpDirection { PUT, GET }
enum class TftpTransferState { RUNNING, COMPLETED, FAILED, CANCELLED }

data class TftpTransferSpec(
    val direction: TftpDirection,
    val host: String,
    val port: Int,
    val remoteFile: String,
    val localFile: File,
    val options: TftpClientOptions,
    val csvLogPath: String = "",
)

/**
 * Per-transfer handle. Holds JavaFX-observable progress/state so any UI (dialog, panel, status
 * bar) can bind. The owning {@link TftpTransferManager} keeps it in its observable list until
 * the user explicitly removes it (or `clearFinished()` runs), so a transfer survives the dialog
 * that submitted it — the user can close the dialog and watch progress from the panel.
 */
class TftpTransferHandle internal constructor(
    val id: Long,
    val spec: TftpTransferSpec,
) {
    val started: Instant = Instant.now()
    private val client = TftpClient()

    private val stateW = ReadOnlyObjectWrapper(TftpTransferState.RUNNING)
    private val transferredW = ReadOnlyLongWrapper(0L)
    private val totalW = ReadOnlyLongWrapper(
        if (spec.direction == TftpDirection.PUT) spec.localFile.length() else -1L
    )
    private val progressW = ReadOnlyDoubleWrapper(-1.0)
    private val errorW = ReadOnlyStringWrapper("")

    val stateProperty: ReadOnlyObjectProperty<TftpTransferState> get() = stateW.readOnlyProperty
    val transferredProperty: ReadOnlyLongProperty get() = transferredW.readOnlyProperty
    val totalProperty: ReadOnlyLongProperty get() = totalW.readOnlyProperty
    val progressProperty: ReadOnlyDoubleProperty get() = progressW.readOnlyProperty
    val errorProperty: ReadOnlyStringProperty get() = errorW.readOnlyProperty

    val state: TftpTransferState get() = stateW.value
    val isRunning: Boolean get() = state == TftpTransferState.RUNNING

    internal fun start() {
        val thread = Thread({
            val csv: TftpCsvLogger? = if (spec.csvLogPath.isBlank()) null else runCatching {
                TftpCsvLogger(Path.of(spec.csvLogPath))
            }.onFailure { LOG.warn("Could not open TFTP CSV {}", spec.csvLogPath, it) }.getOrNull()
            if (csv != null) client.addListener(csv)
            try {
                when (spec.direction) {
                    TftpDirection.PUT -> {
                        BufferedInputStream(Files.newInputStream(spec.localFile.toPath())).use { input ->
                            client.put(spec.host, spec.port, spec.remoteFile, input,
                                spec.localFile.length(), spec.options) { sent, total ->
                                update(sent, if (total > 0) total else spec.localFile.length())
                            }
                        }
                    }
                    TftpDirection.GET -> {
                        BufferedOutputStream(Files.newOutputStream(spec.localFile.toPath())).use { output ->
                            client.get(spec.host, spec.port, spec.remoteFile, output,
                                spec.options) { sent, total -> update(sent, total) }
                        }
                    }
                }
                onFx { progressW.value = 1.0; stateW.value = TftpTransferState.COMPLETED }
            } catch (ex: InterruptedException) {
                onFx { stateW.value = TftpTransferState.CANCELLED }
            } catch (ex: Exception) {
                LOG.warn("TFTP transfer #{} failed", id, ex)
                val msg = ex.message ?: ex.javaClass.simpleName
                // If it failed because cancel() was invoked, mark as CANCELLED instead.
                val cancelledByUser = Thread.currentThread().isInterrupted
                onFx {
                    if (cancelledByUser) {
                        stateW.value = TftpTransferState.CANCELLED
                    } else {
                        errorW.value = msg
                        stateW.value = TftpTransferState.FAILED
                    }
                }
            } finally {
                if (csv != null) {
                    client.removeListener(csv)
                    runCatching { csv.close() }
                }
                TftpTransferManager.notifyTerminal()
            }
        }, "tftp-transfer-$id")
        thread.isDaemon = true
        thread.start()
    }

    fun cancel() {
        if (isRunning) client.cancel()
    }

    private fun update(transferred: Long, total: Long) = onFx {
        transferredW.value = transferred
        if (total > 0) {
            totalW.value = total
            progressW.value = transferred.toDouble() / total
        }
    }

    private fun onFx(block: () -> Unit) {
        if (Platform.isFxApplicationThread()) block() else Platform.runLater(block)
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(TftpTransferHandle::class.java)
    }
}

/**
 * Process-wide registry of TFTP client transfers. Closing the dialog that submitted a transfer
 * does NOT cancel it — the handle stays here until completion (or explicit cancel/remove). The
 * status-bar badge and {@code TftpTransfersPanel} both observe {@link #transfers} and
 * {@link #runningCountProperty}.
 */
object TftpTransferManager {
    private val nextId = AtomicLong(1)
    val transfers: ObservableList<TftpTransferHandle> = FXCollections.observableArrayList()

    private val runningCountW = ReadOnlyIntegerWrapper(0)
    val runningCountProperty: ReadOnlyIntegerProperty get() = runningCountW.readOnlyProperty
    val runningCount: Int get() = runningCountW.value

    fun submit(spec: TftpTransferSpec): TftpTransferHandle {
        val handle = TftpTransferHandle(nextId.getAndIncrement(), spec)
        runOnFx {
            transfers.add(0, handle)        // newest first
            recomputeRunning()
        }
        handle.start()
        return handle
    }

    fun remove(handle: TftpTransferHandle) {
        if (handle.isRunning) return
        runOnFx { transfers.remove(handle) }
    }

    fun clearFinished() = runOnFx {
        transfers.removeIf { !it.isRunning }
    }

    fun cancelAll() {
        transfers.toList().forEach { if (it.isRunning) it.cancel() }
    }

    internal fun notifyTerminal() = runOnFx { recomputeRunning() }

    private fun recomputeRunning() {
        runningCountW.value = transfers.count { it.isRunning }
    }

    private fun runOnFx(block: () -> Unit) {
        if (Platform.isFxApplicationThread()) block() else Platform.runLater(block)
    }
}