package com.opentermx.app.ui.transfer

import com.opentermx.app.viewmodel.TransferController
import javafx.beans.binding.Bindings
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.Window
import java.util.concurrent.Callable

class TransferProgressDialog(
    owner: Window,
    private val controller: TransferController,
    title: String,
) : Stage() {

    init {
        this.title = title
        initOwner(owner)
        initModality(Modality.WINDOW_MODAL)

        val statusLabel = Label().apply {
            textProperty().bind(controller.status)
        }
        val progressBar = ProgressBar().apply {
            maxWidth = Double.MAX_VALUE
            progressProperty().bind(controller.progress)
        }
        val byteCount = Label().apply {
            textProperty().bind(Bindings.createStringBinding(
                Callable {
                    val sent = controller.transferred.value
                    val total = controller.total.value
                    if (total > 0) "$sent / $total bytes" else "$sent bytes"
                },
                controller.transferred, controller.total,
            ))
        }
        val cancelButton = Button("Cancelar").apply {
            setOnAction { controller.cancel() }
        }
        val closeButton = Button("Cerrar").apply {
            isDisable = true
            setOnAction { close() }
        }

        controller.finished.addListener { _, _, result ->
            if (result != null) {
                cancelButton.isDisable = true
                closeButton.isDisable = false
            }
        }

        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        val buttons = HBox(8.0, spacer, cancelButton, closeButton)

        val layout = VBox(10.0, statusLabel, progressBar, byteCount, buttons).apply {
            padding = Insets(16.0)
            minWidth = 420.0
        }
        scene = Scene(layout)
        setOnCloseRequest { e ->
            if (controller.finished.value == null) {
                controller.cancel()
                e.consume()
            }
        }
    }
}