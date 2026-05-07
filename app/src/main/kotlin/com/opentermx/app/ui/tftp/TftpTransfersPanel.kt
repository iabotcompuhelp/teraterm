package com.opentermx.app.ui.tftp

import com.opentermx.app.i18n.Strings
import javafx.beans.InvalidationListener
import javafx.beans.binding.Bindings
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.Window
import java.time.format.DateTimeFormatter
import java.time.ZoneId

/**
 * Floating panel listing every transfer in {@link TftpTransferManager}. Non-modal — opens from
 * the status-bar badge or File → Transfer menu, stays alive while the user keeps configuring.
 * Each row binds to its handle's properties so progress updates without rebuilding.
 */
class TftpTransfersPanel(owner: Window) : Stage() {

    private val rowsBox = VBox(8.0)
    private val emptyLabel = Label(Strings["tftp.transfers.empty"]).apply {
        styleClass += "tftp-transfers-empty"
    }
    private val invalidationListener = InvalidationListener { rebuild() }
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    init {
        title = Strings["tftp.transfers.title"]
        initOwner(owner)
        initModality(Modality.NONE)

        val clearBtn = Button(Strings["tftp.transfers.clearFinished"]).apply {
            setOnAction { TftpTransferManager.clearFinished() }
        }
        val closeBtn = Button(Strings["tftp.close"]).apply {
            setOnAction { close() }
        }
        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        val buttons = HBox(8.0, spacer, clearBtn, closeBtn)

        val scroll = ScrollPane(rowsBox).apply {
            isFitToWidth = true
            VBox.setVgrow(this, Priority.ALWAYS)
        }
        rowsBox.padding = Insets(8.0)

        val layout = VBox(10.0, scroll, buttons).apply {
            padding = Insets(12.0)
            minWidth = 560.0
            minHeight = 360.0
        }
        scene = Scene(layout)

        setOnShown {
            TftpTransferManager.transfers.addListener(invalidationListener)
            rebuild()
        }
        setOnHidden {
            TftpTransferManager.transfers.removeListener(invalidationListener)
        }
        rebuild()
    }

    private fun rebuild() {
        val items = TftpTransferManager.transfers
        if (items.isEmpty()) {
            rowsBox.children.setAll(emptyLabel)
        } else {
            rowsBox.children.setAll(items.map { buildRow(it) })
        }
    }

    private fun buildRow(handle: TftpTransferHandle): Node {
        val dir = if (handle.spec.direction == TftpDirection.PUT)
            Strings["tftp.transfers.put"] else Strings["tftp.transfers.get"]
        val target = "${handle.spec.host}:${handle.spec.port}  ${handle.spec.remoteFile}"
        val ts = timeFmt.format(handle.started)
        val header = Label("[$ts] $dir  $target").apply { styleClass += "tftp-row-header" }

        val progress = ProgressBar(0.0).apply {
            maxWidth = Double.MAX_VALUE
            progressProperty().bind(handle.progressProperty)
            HBox.setHgrow(this, Priority.ALWAYS)
        }
        val statusBinding = Bindings.createStringBinding(
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
        )
        val statusLabel = Label().apply { textProperty().bind(statusBinding) }

        val cancelBtn = Button(Strings["tftp.cancel"]).apply {
            setOnAction { handle.cancel() }
            visibleProperty().bind(Bindings.equal(handle.stateProperty, TftpTransferState.RUNNING))
            managedProperty().bind(visibleProperty())
        }
        val removeBtn = Button(Strings["tftp.transfers.remove"]).apply {
            setOnAction { TftpTransferManager.remove(handle) }
            visibleProperty().bind(Bindings.notEqual(handle.stateProperty, TftpTransferState.RUNNING))
            managedProperty().bind(visibleProperty())
        }
        val actions = HBox(6.0, cancelBtn, removeBtn).apply { alignment = Pos.CENTER_RIGHT }

        val progressRow = HBox(8.0, progress, statusLabel, actions).apply {
            alignment = Pos.CENTER_LEFT
        }
        return VBox(4.0, header, progressRow).apply {
            padding = Insets(8.0)
            styleClass += "tftp-transfer-row"
        }
    }
}