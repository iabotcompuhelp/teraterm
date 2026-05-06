package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.AdditionalSettings
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.stage.DirectoryChooser
import javafx.stage.FileChooser
import java.io.File

class AdditionalSettingsDialog(initial: AdditionalSettings) : Dialog<AdditionalSettings>() {

    private val showNotifs = CheckBox(Strings["setup.additional.notifications"])
        .apply { isSelected = initial.showNotifications }
    private val copyOnSelect = CheckBox(Strings["setup.additional.copyOnSelect"])
        .apply { isSelected = initial.copyOnSelect }
    private val blinkText = CheckBox(Strings["setup.additional.blinkText"])
        .apply { isSelected = initial.blinkText }
    private val visualCursor = CheckBox(Strings["setup.additional.visualCursor"])
        .apply { isSelected = initial.visualCursorBlink }
    private val logFormatCombo = ComboBox<String>().apply {
        items.addAll("TXT", "HTML", "RAW"); value = initial.defaultLogFormat
    }
    private val logDirField = TextField(initial.defaultLogDir)
    private val browseLogDir = Button(Strings["setup.additional.browseDir"]).apply {
        setOnAction {
            val dir: File? = DirectoryChooser().apply {
                title = Strings["setup.additional.logDir"]
                val current = File(logDirField.text)
                if (current.isDirectory) initialDirectory = current
            }.showDialog(dialogPane.scene?.window)
            if (dir != null) logDirField.text = dir.absolutePath
        }
    }
    private val autoLogCheck = CheckBox(Strings["setup.additional.autoLog"])
        .apply { isSelected = initial.autoLogOnConnect }
    private val tftpPortSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 65535, initial.tftpDefaultPort, 1)
        isEditable = true
    }
    private val tftpRootField = TextField(initial.tftpDefaultRoot)
    private val browseTftpRoot = Button(Strings["setup.additional.browseDir"]).apply {
        setOnAction {
            val dir: File? = DirectoryChooser().apply {
                title = Strings["setup.additional.tftpRoot"]
                val current = File(tftpRootField.text)
                if (current.isDirectory) initialDirectory = current
            }.showDialog(dialogPane.scene?.window)
            if (dir != null) tftpRootField.text = dir.absolutePath
        }
    }
    private val tftpBlockSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(8, 65464, initial.tftpDefaultBlocksize, 64)
        isEditable = true
    }
    private val tftpCsvField = TextField(initial.tftpCsvLogPath)
    private val browseTftpCsv = Button(Strings["setup.additional.browseDir"]).apply {
        setOnAction {
            val file: File? = FileChooser().apply {
                title = Strings["setup.additional.tftpCsv"]
                initialFileName = "tftp.csv"
                extensionFilters.add(FileChooser.ExtensionFilter("CSV", "*.csv"))
            }.showSaveDialog(dialogPane.scene?.window)
            if (file != null) tftpCsvField.text = file.absolutePath
        }
    }
    private val autoLoginField = TextField(initial.autoLoginMacroPath)
    private val browseAutoLogin = Button(Strings["setup.additional.browseDir"]).apply {
        setOnAction {
            val file: File? = FileChooser().apply {
                title = Strings["setup.additional.autoLogin"]
                extensionFilters.addAll(
                    FileChooser.ExtensionFilter("Macro", "*.groovy", "*.ttl"),
                    FileChooser.ExtensionFilter("All files", "*.*"),
                )
            }.showOpenDialog(dialogPane.scene?.window)
            if (file != null) autoLoginField.text = file.absolutePath
        }
    }

    init {
        title = Strings["setup.additional.title"]
        headerText = Strings["setup.additional.header"]
        val tabs = TabPane().apply {
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
            tabs += Tab(Strings["setup.additional.tabGeneral"], GridPane().apply {
                hgap = 10.0; vgap = 8.0; padding = Insets(16.0)
                var r = 0
                add(showNotifs, 0, r, 2, 1); r++
                add(copyOnSelect, 0, r, 2, 1); r++
                add(Label(Strings["setup.additional.autoLogin"]), 0, r)
                add(HBox(6.0, autoLoginField, browseAutoLogin), 1, r); r++
            })
            tabs += Tab(Strings["setup.additional.tabVisual"], GridPane().apply {
                hgap = 10.0; vgap = 8.0; padding = Insets(16.0)
                var r = 0
                add(blinkText, 0, r); r++
                add(visualCursor, 0, r); r++
            })
            tabs += Tab(Strings["setup.additional.tabLog"], GridPane().apply {
                hgap = 10.0; vgap = 8.0; padding = Insets(16.0)
                var r = 0
                add(Label(Strings["setup.additional.logFormat"]), 0, r); add(logFormatCombo, 1, r); r++
                add(Label(Strings["setup.additional.logDir"]), 0, r)
                add(HBox(6.0, logDirField, browseLogDir), 1, r); r++
                add(autoLogCheck, 1, r); r++
            })
            tabs += Tab(Strings["setup.additional.tabTftp"], GridPane().apply {
                hgap = 10.0; vgap = 8.0; padding = Insets(16.0)
                var r = 0
                add(Label(Strings["setup.additional.tftpPort"]), 0, r); add(tftpPortSpinner, 1, r); r++
                add(Label(Strings["setup.additional.tftpRoot"]), 0, r)
                add(HBox(6.0, tftpRootField, browseTftpRoot), 1, r); r++
                add(Label(Strings["setup.additional.tftpBlocksize"]), 0, r); add(tftpBlockSpinner, 1, r); r++
                add(Label(Strings["setup.additional.tftpCsv"]), 0, r)
                add(HBox(6.0, tftpCsvField, browseTftpCsv), 1, r); r++
            })
        }
        dialogPane.content = tabs
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        setResultConverter { btn ->
            if (btn != ButtonType.OK) null else AdditionalSettings(
                showNotifications = showNotifs.isSelected,
                copyOnSelect = copyOnSelect.isSelected,
                blinkText = blinkText.isSelected,
                visualCursorBlink = visualCursor.isSelected,
                defaultLogFormat = logFormatCombo.value,
                defaultLogDir = logDirField.text.ifBlank { System.getProperty("user.home") },
                autoLogOnConnect = autoLogCheck.isSelected,
                tftpDefaultPort = tftpPortSpinner.value,
                tftpDefaultRoot = tftpRootField.text.ifBlank { System.getProperty("user.home") },
                tftpDefaultBlocksize = tftpBlockSpinner.value,
                tftpCsvLogPath = tftpCsvField.text.trim(),
                autoLoginMacroPath = autoLoginField.text.trim(),
            )
        }
    }
}