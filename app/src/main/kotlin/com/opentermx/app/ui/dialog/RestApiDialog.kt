package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.app.rest.RestApiManager
import com.opentermx.app.settings.RestApiPersistedSettings
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.control.TextField
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import java.io.File

/**
 * Setup → REST API…  (spec v4 § Grupo 4, integración externa).
 *
 * Compacto: toggle enabled, bind host (127.0.0.1 / 0.0.0.0), port, token con copy/regen,
 * checkbox requireAuth, CSV audit log path opcional.
 */
class RestApiDialog(initial: RestApiPersistedSettings) : Dialog<RestApiPersistedSettings>() {

    private val enabledCheck = CheckBox(Strings["setup.restApi.enable"]).apply { isSelected = initial.enabled }
    private val bindCombo = ComboBox<String>().apply {
        isEditable = true
        items.addAll("127.0.0.1", "0.0.0.0")
        value = initial.bindHost.ifBlank { "127.0.0.1" }
    }
    private val portSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 65535, initial.port.coerceIn(1, 65535), 1)
        isEditable = true
        prefWidth = 110.0
    }
    private val tokenField = TextField(initial.token).apply { prefColumnCount = 36 }
    private val regenBtn = Button(Strings["setup.restApi.regen"]).apply {
        setOnAction { tokenField.text = RestApiManager.generateToken() }
    }
    private val copyTokenBtn = Button(Strings["setup.restApi.copy"]).apply {
        setOnAction {
            val cc = ClipboardContent().apply { putString(tokenField.text) }
            Clipboard.getSystemClipboard().setContent(cc)
        }
    }
    private val requireAuthCheck = CheckBox(Strings["setup.restApi.requireAuth"])
        .apply { isSelected = initial.requireAuth }
    private val logField = TextField(initial.apiLogPath).apply { prefColumnCount = 32 }
    private val browseLog = Button(Strings["setup.ai.kb.add"].substringBefore(' ')).apply { // reusamos icono Folder
        text = Strings["setup.restApi.logBrowse"]
        setOnAction {
            val file: File? = FileChooser().apply {
                title = Strings["setup.restApi.logBrowseTitle"]
                initialFileName = "api-log.csv"
                extensionFilters.add(FileChooser.ExtensionFilter("CSV", "*.csv"))
            }.showSaveDialog(dialogPane.scene?.window)
            if (file != null) logField.text = file.absolutePath
        }
    }
    private val statusLabel = Label("").apply {
        style = "-fx-text-fill: derive(-fx-text-base-color, 30%); -fx-font-size: 11px;"
    }
    private val urlPreview = Label("").apply {
        style = "-fx-font-family: 'Consolas','Menlo',monospace; -fx-font-size: 11px;"
    }

    init {
        title = Strings["setup.restApi.title"]
        headerText = Strings["setup.restApi.header"]
        isResizable = false

        if (tokenField.text.isBlank()) tokenField.text = RestApiManager.generateToken()

        val refreshPreview = {
            urlPreview.text = "http://${bindCombo.value ?: "127.0.0.1"}:${portSpinner.value}/api/"
        }
        refreshPreview()
        bindCombo.valueProperty().addListener { _, _, _ -> refreshPreview() }
        portSpinner.valueProperty().addListener { _, _, _ -> refreshPreview() }

        statusLabel.text = if (RestApiManager.isRunning) {
            Strings.format("setup.restApi.statusRunning", RestApiManager.activePort)
        } else {
            Strings["setup.restApi.statusStopped"]
        }

        val grid = GridPane().apply {
            hgap = 10.0; vgap = 8.0; padding = Insets(16.0)
            var r = 0
            add(enabledCheck, 0, r, 2, 1); r++
            add(Label(Strings["setup.restApi.bindHost"]), 0, r); add(bindCombo, 1, r); r++
            add(Label(Strings["setup.restApi.port"]), 0, r); add(portSpinner, 1, r); r++
            add(Label(Strings["setup.restApi.token"]), 0, r)
            add(HBox(6.0, tokenField, regenBtn, copyTokenBtn).apply { alignment = Pos.CENTER_LEFT }, 1, r); r++
            add(requireAuthCheck, 1, r); r++
            add(Label(Strings["setup.restApi.log"]), 0, r)
            add(HBox(6.0, logField, browseLog).apply { alignment = Pos.CENTER_LEFT }, 1, r); r++
            add(Label(Strings["setup.restApi.endpointPreview"]), 0, r); add(urlPreview, 1, r); r++
            add(Label(""), 0, r); add(statusLabel, 1, r); r++
        }
        val tip = Label(Strings["setup.restApi.privacyNote"]).apply { isWrapText = true; maxWidth = 520.0 }
        dialogPane.content = VBox(8.0, grid, tip).apply { padding = Insets(4.0) }
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)

        setResultConverter { btn ->
            if (btn != ButtonType.OK) return@setResultConverter null
            RestApiPersistedSettings(
                enabled = enabledCheck.isSelected,
                bindHost = (bindCombo.value ?: "127.0.0.1").trim().ifBlank { "127.0.0.1" },
                port = portSpinner.value,
                token = tokenField.text.trim(),
                requireAuth = requireAuthCheck.isSelected,
                apiLogPath = logField.text.trim(),
            )
        }
    }
}
