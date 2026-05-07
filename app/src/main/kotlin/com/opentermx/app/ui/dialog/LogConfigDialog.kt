package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
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

class LogConfigDialog(
    suggestedName: String = "session",
    defaultDir: String = Paths.get(System.getProperty("user.home"), ".opentermx", "logs").toString(),
    defaultFormat: String = "TXT",
    defaultTimestamps: Boolean = true,
    defaultTimestampPattern: String = "yyyy-MM-dd HH:mm:ss.SSS",
    defaultRotation: String = "NONE",       // NONE | BY_SIZE | BY_TIME
    defaultRotationSizeMb: Int = 10,
    defaultRotationMinutes: Int = 60,
) : Dialog<LogConfig>() {

    private val pathField = TextField().apply {
        text = Paths.get(defaultDir, suggestedName).toString()
    }
    private val browseButton = Button(Strings["log.browse"])

    private val formatGroup = ToggleGroup()
    private val plainRadio = RadioButton(Strings["log.format.plain"]).apply { toggleGroup = formatGroup }
    private val htmlRadio = RadioButton(Strings["log.format.html"]).apply { toggleGroup = formatGroup }
    private val rawRadio = RadioButton(Strings["log.format.raw"]).apply { toggleGroup = formatGroup }

    private val timestampsCheck = CheckBox(Strings["log.timestampsCheck"])
        .apply { isSelected = defaultTimestamps }
    private val timestampPattern = TextField(defaultTimestampPattern)

    private val rotationGroup = ToggleGroup()
    private val noRotationRadio = RadioButton(Strings["log.rotation.none"]).apply { toggleGroup = rotationGroup }
    private val sizeRotationRadio = RadioButton(Strings["log.rotation.bySize"]).apply { toggleGroup = rotationGroup }
    private val timeRotationRadio = RadioButton(Strings["log.rotation.byTime"]).apply { toggleGroup = rotationGroup }

    private val sizeMbSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 4096, defaultRotationSizeMb)
        isEditable = true
    }
    private val timeMinutesSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 1440, defaultRotationMinutes)
        isEditable = true
    }

    init {
        title = Strings["log.title"]
        headerText = Strings["log.header"]

        when (defaultFormat.uppercase()) {
            "HTML" -> htmlRadio.isSelected = true
            "RAW" -> rawRadio.isSelected = true
            else -> plainRadio.isSelected = true
        }
        when (defaultRotation.uppercase()) {
            "BY_SIZE" -> sizeRotationRadio.isSelected = true
            "BY_TIME" -> timeRotationRadio.isSelected = true
            else -> noRotationRadio.isSelected = true
        }

        browseButton.setOnAction {
            val current = Path.of(pathField.text)
            val chooser = FileChooser().apply {
                title = Strings["log.fileChooser"]
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
            add(Label(Strings["log.path"]), 0, 0); add(pathRow, 1, 0)
            add(Label(Strings["log.format"]), 0, 1); add(HBox(8.0, plainRadio, htmlRadio, rawRadio), 1, 1)
            add(Label(Strings["log.timestamps"]), 0, 2); add(HBox(8.0, timestampsCheck, timestampPattern), 1, 2)
            add(Label(Strings["log.rotation"]), 0, 3); add(HBox(8.0, noRotationRadio, sizeRotationRadio, timeRotationRadio), 1, 3)
            add(Label(Strings["log.maxSize"]), 0, 4); add(sizeMbSpinner, 1, 4)
            add(Label(Strings["log.interval"]), 0, 5); add(timeMinutesSpinner, 1, 5)
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