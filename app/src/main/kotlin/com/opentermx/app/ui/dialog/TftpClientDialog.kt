package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.app.ui.tftp.TftpDirection
import com.opentermx.app.ui.tftp.TftpTransferHandle
import com.opentermx.app.ui.tftp.TftpTransferManager
import com.opentermx.app.ui.tftp.TftpTransferSpec
import com.opentermx.app.ui.tftp.TftpTransferState
import com.opentermx.tftp.client.TftpClientOptions
import com.opentermx.tftp.common.TransferMode
import javafx.beans.binding.Bindings
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.control.RadioButton
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.control.TextField
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.Window
import java.io.File

/**
 * "Submit form" for TFTP transfers. The dialog no longer owns the worker thread — pressing
 * Transfer hands the spec to {@link TftpTransferManager} and binds the visible progress bar to
 * the returned handle. Closing the dialog leaves the transfer running; users watch progress
 * from the status-bar badge / {@code TftpTransfersPanel}.
 */
class TftpClientDialog(
    owner: Window,
    initialHost: String = "",
    defaultPort: Int = 69,
    defaultBlockSize: Int = 512,
    private val csvLogPath: String = "",
) : Stage() {

    private val hostField = TextField(initialHost)
    private val portSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 65535, defaultPort, 1)
        isEditable = true
    }
    private val directionGroup = ToggleGroup()
    private val sendRadio = RadioButton(Strings["tftp.send"]).apply {
        toggleGroup = directionGroup; isSelected = true
    }
    private val receiveRadio = RadioButton(Strings["tftp.receive"]).apply {
        toggleGroup = directionGroup
    }
    private val remoteFileField = TextField()
    private val localFileField = TextField()
    private val browseButton = Button(Strings["tftp.browse"]).apply {
        setOnAction { browseLocal() }
    }
    private val modeCombo = ComboBox<TransferMode>().apply {
        items.addAll(TransferMode.OCTET, TransferMode.NETASCII)
        value = TransferMode.OCTET
    }
    private val blockSizeSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(8, 65464, defaultBlockSize, 64)
        isEditable = true
    }
    private val timeoutSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 60, 5, 1)
        isEditable = true
    }
    private val progressBar = ProgressBar(0.0).apply { maxWidth = Double.MAX_VALUE }
    private val statusLabel = Label(Strings["tftp.idle"])
    private val transferButton = Button(Strings["tftp.transfer"])
    private val cancelButton = Button(Strings["tftp.cancel"])
    private val closeButton = Button(Strings["tftp.close"]).apply { setOnAction { close() } }

    private var currentHandle: TftpTransferHandle? = null

    init {
        title = Strings["tftp.clientTitle"]
        initOwner(owner)
        initModality(Modality.NONE)

        val grid = GridPane().apply {
            hgap = 8.0; vgap = 8.0
            padding = Insets(16.0)

            add(Label(Strings["tftp.host"]), 0, 0); add(hostField, 1, 0, 3, 1)
            add(Label(Strings["tftp.port"]), 0, 1); add(portSpinner, 1, 1)

            val dirBox = HBox(12.0, sendRadio, receiveRadio)
            add(Label(Strings["tftp.direction"]), 0, 2); add(dirBox, 1, 2, 3, 1)

            add(Label(Strings["tftp.remoteFile"]), 0, 3); add(remoteFileField, 1, 3, 3, 1)

            add(Label(Strings["tftp.localFile"]), 0, 4); add(localFileField, 1, 4, 2, 1); add(browseButton, 3, 4)

            add(Label(Strings["tftp.mode"]), 0, 5); add(modeCombo, 1, 5)
            add(Label(Strings["tftp.blockSize"]), 0, 6); add(blockSizeSpinner, 1, 6)
            add(Label(Strings["tftp.timeout"]), 0, 7); add(timeoutSpinner, 1, 7)
        }

        hostField.maxWidth = Double.MAX_VALUE
        remoteFileField.maxWidth = Double.MAX_VALUE
        localFileField.maxWidth = Double.MAX_VALUE
        GridPane.setHgrow(hostField, Priority.ALWAYS)
        GridPane.setHgrow(remoteFileField, Priority.ALWAYS)
        GridPane.setHgrow(localFileField, Priority.ALWAYS)

        transferButton.setOnAction { startTransfer() }
        cancelButton.setOnAction { currentHandle?.cancel() }
        cancelButton.isDisable = true

        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        val buttons = HBox(8.0, spacer, transferButton, cancelButton, closeButton)
        val layout = VBox(10.0, grid, progressBar, statusLabel, buttons).apply {
            padding = Insets(0.0, 16.0, 16.0, 16.0)
            minWidth = 480.0
        }
        scene = Scene(layout)
        // Close just hides the window — the transfer keeps running in TftpTransferManager.
    }

    private fun browseLocal() {
        val chooser = FileChooser().apply { title = Strings["tftp.browse"] }
        val file: File? = if (sendRadio.isSelected) chooser.showOpenDialog(this) else chooser.showSaveDialog(this)
        if (file != null) {
            localFileField.text = file.absolutePath
            if (remoteFileField.text.isBlank()) remoteFileField.text = file.name
        }
    }

    private fun startTransfer() {
        val host = hostField.text.trim()
        if (host.isEmpty()) { statusLabel.text = Strings["tftp.errHost"]; return }
        val port = portSpinner.value
        val remote = remoteFileField.text.trim()
        if (remote.isEmpty()) { statusLabel.text = Strings["tftp.errRemote"]; return }
        val localPath = localFileField.text.trim()
        if (localPath.isEmpty()) { statusLabel.text = Strings["tftp.errLocal"]; return }

        val opts = TftpClientOptions(
            modeCombo.value ?: TransferMode.OCTET,
            blockSizeSpinner.value,
            timeoutSpinner.value,
            5,
            true,
        )
        val isSend = sendRadio.isSelected
        val localFile = File(localPath)
        if (isSend && !localFile.isFile) {
            statusLabel.text = Strings.format("tftp.errLocalMissing", localFile.name)
            return
        }

        val spec = TftpTransferSpec(
            direction = if (isSend) TftpDirection.PUT else TftpDirection.GET,
            host = host,
            port = port,
            remoteFile = remote,
            localFile = localFile,
            options = opts,
            csvLogPath = csvLogPath,
        )
        val handle = TftpTransferManager.submit(spec)
        bindToHandle(handle)
    }

    private fun bindToHandle(handle: TftpTransferHandle) {
        currentHandle = handle
        progressBar.progressProperty().unbind()
        progressBar.progressProperty().bind(handle.progressProperty)
        statusLabel.textProperty().unbind()
        statusLabel.textProperty().bind(Bindings.createStringBinding(
            {
                when (handle.state) {
                    TftpTransferState.RUNNING -> {
                        val total = handle.totalProperty.value
                        val sent = handle.transferredProperty.value
                        if (total > 0) Strings.format("tftp.progress", sent, total)
                        else Strings.format("tftp.progressUnknown", sent)
                    }
                    TftpTransferState.COMPLETED -> Strings["tftp.completed"]
                    TftpTransferState.CANCELLED -> Strings["tftp.cancelled"]
                    TftpTransferState.FAILED -> Strings.format("tftp.failed", handle.errorProperty.value)
                }
            },
            handle.stateProperty, handle.transferredProperty, handle.totalProperty, handle.errorProperty,
        ))
        setRunning(true)
        handle.stateProperty.addListener { _, _, newState ->
            if (newState != TftpTransferState.RUNNING) setRunning(false)
        }
    }

    private fun setRunning(running: Boolean) {
        transferButton.isDisable = running
        cancelButton.isDisable = !running
        listOf(hostField, portSpinner, sendRadio, receiveRadio,
                remoteFileField, localFileField, browseButton,
                modeCombo, blockSizeSpinner, timeoutSpinner)
            .forEach { it.isDisable = running }
    }
}