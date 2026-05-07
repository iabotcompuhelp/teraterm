package com.opentermx.app.viewmodel

import com.opentermx.common.connection.Connection
import com.opentermx.transfer.TransferDirection
import com.opentermx.transfer.TransferListener
import com.opentermx.transfer.TransferStream
import com.opentermx.transfer.Xmodem
import javafx.application.Platform
import javafx.beans.property.ReadOnlyDoubleProperty
import javafx.beans.property.ReadOnlyDoubleWrapper
import javafx.beans.property.ReadOnlyLongProperty
import javafx.beans.property.ReadOnlyLongWrapper
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.ReadOnlyStringProperty
import javafx.beans.property.ReadOnlyStringWrapper
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

enum class TransferProtocol { XMODEM }

data class TransferResult(val success: Boolean, val error: Throwable?)

class TransferController(
    private val connection: Connection,
    private val direction: TransferDirection,
    private val target: File,
    private val fileSize: Long,
    private val protocol: TransferProtocol = TransferProtocol.XMODEM,
    private val batchFiles: List<File> = listOf(target),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val progressWrapper = ReadOnlyDoubleWrapper(0.0)
    private val statusWrapper = ReadOnlyStringWrapper("Esperando…")
    private val transferredWrapper = ReadOnlyLongWrapper(0)
    private val totalWrapper = ReadOnlyLongWrapper(fileSize)
    private val finishedWrapper = ReadOnlyObjectWrapper<TransferResult?>(null)

    val progress: ReadOnlyDoubleProperty get() = progressWrapper.readOnlyProperty
    val status: ReadOnlyStringProperty get() = statusWrapper.readOnlyProperty
    val transferred: ReadOnlyLongProperty get() = transferredWrapper.readOnlyProperty
    val total: ReadOnlyLongProperty get() = totalWrapper.readOnlyProperty
    val finished: ReadOnlyObjectProperty<TransferResult?> get() = finishedWrapper.readOnlyProperty

    private val cancelled = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        thread = Thread({
            val stream = TransferStream.forConnection(connection)
            val listener = object : TransferListener {
                override fun onProgress(bytesTransferred: Long, totalBytes: Long) {
                    Platform.runLater {
                        transferredWrapper.value = bytesTransferred
                        if (totalBytes > 0) {
                            totalWrapper.value = totalBytes
                            progressWrapper.value = bytesTransferred.toDouble() / totalBytes
                        }
                    }
                }
                override fun onMessage(message: String) {
                    Platform.runLater { statusWrapper.value = message }
                }
                override fun isCancelled() = cancelled.get()
            }
            try {
                runProtocol(stream, listener)
                Platform.runLater {
                    statusWrapper.value = "Completado"
                    progressWrapper.value = 1.0
                    finishedWrapper.value = TransferResult(true, null)
                }
            } catch (e: Throwable) {
                log.warn("Transferencia fallida", e)
                Platform.runLater {
                    statusWrapper.value = "Error: ${e.message ?: e.javaClass.simpleName}"
                    finishedWrapper.value = TransferResult(false, e)
                }
            } finally {
                stream.close()
            }
        }, "${protocol.name.lowercase()}-${direction.name.lowercase()}").apply { isDaemon = true }
        thread?.start()
    }

    private fun runProtocol(stream: TransferStream, listener: TransferListener) {
        when (protocol) {
            TransferProtocol.XMODEM -> when (direction) {
                TransferDirection.SEND -> FileInputStream(target).use {
                    Xmodem.send(stream, it, fileSize, listener)
                }
                TransferDirection.RECEIVE -> FileOutputStream(target).use {
                    Xmodem.receive(stream, it, listener)
                }
            }
        }
    }

    fun cancel() {
        cancelled.set(true)
        thread?.interrupt()
    }
}