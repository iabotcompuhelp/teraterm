package com.opentermx.app.ui.dialog

import com.opentermx.logger.LogConfig
import com.opentermx.logger.LogFormat
import com.opentermx.logger.RotationPolicy
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.control.TextField
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.stage.FileChooser
import java.nio.file.Path
import java.nio.file.Paths

class LogConfigDialog(suggestedName: String = "session") : Dialog<LogConfig>() {

    private val pathField = TextField().apply {
        text = Paths.get(System.getProperty("user.home"), ".opentermx", "logs", suggestedName).toString()
    }
    private val browseButton = Button("Buscar…")

    private val formatGroup = ToggleGroup()
    private val plainRadio = RadioButton("Texto plano (.log)").apply { toggleGroup = formatGroup; isSelected = true }
    private val htmlRadio = RadioButton("HTML con colores").apply { toggleGroup = formatGroup }
    private val rawRadio = RadioButton("Bytes crudos").apply { toggleGroup = formatGroup }

    private val timestampsCheck = CheckBox("Timestamp por línea").apply { isSelected = true }
    private val timestampPattern = TextField("yyyy-MM-dd HH:mm:ss.SSS")

    private val rotationGroup = ToggleGroup()
    private val noRotationRadio = RadioButton("Sin rotación").apply { toggleGroup = rotationGroup; isSelected = true }
    private val sizeRotationRadio = RadioButton("Por tamaño").apply { toggleGroup = rotationGroup }
    private val timeRotationRadio = RadioButton("Por tiempo").apply { toggleGroup = rotationGroup }

    private val sizeMbSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 4096, 10)
    }
    private val timeMinutesSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 1440, 60)
    }

    init {
        title = "Configurar log de sesión"
        headerText = "Capturar la sesión a archivo"

        browseButton.setOnAction {
            val current = Path.of(pathField.text)
            val chooser = FileChooser().apply {
                title = "Archivo destino"
                initialFileName = current.fileName?.toString() ?: suggestedName
                current.parent?.toFile()?.takeIf { it.isDirectory }?.let { initialDirectory = it }
            }
            chooser.showSaveDialog(dialogPane.scene.window)?.let { pathField.text = it.absolutePath }
        }

        val pathRow = HBox(6.0, pathField, browseButton).also {
            HBox.setHgrow(pathField, Priority.ALWAYS)
        }
        pathField.maxWidth = Double.MAX_VALUE
        timestampPattern.maxWidth = Double.MAX_VALUE

        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 8.0
            padding = Insets(20.0)
            add(Label("Archivo:"), 0, 0); add(pathRow, 1, 0)
            add(Label("Formato:"), 0, 1); add(HBox(8.0, plainRadio, htmlRadio, rawRadio), 1, 1)
            add(Label("Timestamps:"), 0, 2); add(HBox(8.0, timestampsCheck, timestampPattern), 1, 2)
            add(Label("Rotación:"), 0, 3); add(HBox(8.0, noRotationRadio, sizeRotationRadio, timeRotationRadio), 1, 3)
            add(Label("Tamaño máx (MB):"), 0, 4); add(sizeMbSpinner, 1, 4)
            add(Label("Intervalo (min):"), 0, 5); add(timeMinutesSpinner, 1, 5)
        }
        dialogPane.content = grid

        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        dialogPane.lookupButton(ButtonType.OK).disableProperty().bind(pathField.textProperty().isEmpty)
        setResultConverter { btn -> if (btn == ButtonType.OK) buildConfig() else null }
    }

    private fun buildConfig(): LogConfig? {
        val format = when {
            plainRadio.isSelected -> LogFormat.PLAIN
            htmlRadio.isSelected -> LogFormat.HTML
            rawRadio.isSelected -> LogFormat.RAW
            else -> LogFormat.PLAIN
        }
        val rotation: RotationPolicy = when {
            sizeRotationRadio.isSelected -> RotationPolicy.BySize(sizeMbSpinner.value.toLong() * 1024 * 1024)
            timeRotationRadio.isSelected -> RotationPolicy.ByTime(timeMinutesSpinner.value.toLong() * 60_000L)
            else -> RotationPolicy.None
        }
        return LogConfig(
            basePath = Path.of(pathField.text),
            format = format,
            timestamps = timestampsCheck.isSelected,
            timestampPattern = timestampPattern.text.ifBlank { "yyyy-MM-dd HH:mm:ss.SSS" },
            rotation = rotation,
        )
    }
}