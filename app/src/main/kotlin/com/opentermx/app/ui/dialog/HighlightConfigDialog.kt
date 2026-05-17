package com.opentermx.app.ui.dialog

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.opentermx.app.ui.terminal.highlight.CustomHighlightRule
import com.opentermx.app.ui.terminal.highlight.HighlightSettings
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TitledPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import java.io.File

/**
 * Dialog principal de configuración del resaltado (Setup → Resaltado…).
 * Devuelve el `HighlightSettings` resultante al confirmar (OK) — `null` al cancelar.
 *
 * Layout:
 *  - Toggle global "Habilitar resaltado contextual".
 *  - Bloque "Built-in" con 4 toggles (prompt, keywords, comandos, skip alt-screen).
 *  - Tabla de reglas custom con columnas (enabled, name, pattern, fg, priority).
 *  - Botones Add / Edit / Delete + Import JSON / Export JSON.
 */
class HighlightConfigDialog(initial: HighlightSettings) : Dialog<HighlightSettings>() {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
        .enable(SerializationFeature.INDENT_OUTPUT)

    private val enabledCheck = CheckBox("Habilitar resaltado contextual").apply {
        isSelected = initial.enabled
    }
    private val promptCheck = CheckBox("Detectar modo del prompt (>, #, $, (config)#)").apply {
        isSelected = initial.promptDetectionEnabled
    }
    private val keywordsCheck = CheckBox("Palabras clave en salida (error, up, down, warning, …)").apply {
        isSelected = initial.keywordsEnabled
    }
    private val commandsCheck = CheckBox("Comandos (show, configure, interface, ping, …)").apply {
        isSelected = initial.commandsEnabled
    }
    private val skipAltCheck = CheckBox("Desactivar dentro de vim/top/less (pantalla alterna)").apply {
        isSelected = initial.skipOnAlternateScreen
    }

    private val customRules: MutableList<CustomHighlightRule> = initial.customRules.toMutableList()
    private val rulesTable = TableView<CustomHighlightRule>()

    init {
        title = "Resaltado del terminal"
        headerText = "Resaltado visual contextual"

        configureRulesTable()
        refreshTable()

        val builtinPane = TitledPane("Built-in", VBox(6.0, promptCheck, keywordsCheck, commandsCheck, skipAltCheck)).apply {
            isCollapsible = false
        }
        val tableToolbar = HBox(
            6.0,
            Button("Agregar").apply { setOnAction { addRule() } },
            Button("Editar").apply {
                disableProperty().bind(rulesTable.selectionModel.selectedItemProperty().isNull)
                setOnAction { editRule() }
            },
            Button("Eliminar").apply {
                disableProperty().bind(rulesTable.selectionModel.selectedItemProperty().isNull)
                setOnAction { deleteRule() }
            },
            Button("Importar JSON…").apply { setOnAction { importJson() } },
            Button("Exportar JSON…").apply {
                disableProperty().bind(javafx.beans.binding.Bindings.size(rulesTable.items).isEqualTo(0))
                setOnAction { exportJson() }
            },
        )
        val rulesPane = TitledPane("Reglas custom", VBox(6.0, rulesTable, tableToolbar).apply {
            VBox.setVgrow(rulesTable, Priority.ALWAYS)
            prefHeight = 240.0
        }).apply { isCollapsible = false }

        val content = VBox(10.0, enabledCheck, builtinPane, rulesPane).apply {
            padding = Insets(16.0)
            prefWidth = 620.0
            VBox.setVgrow(rulesPane, Priority.ALWAYS)
        }
        dialogPane.content = content
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        // Cuando enabled global = false, los toggles built-in y la tabla se mantienen visibles
        // pero NO se aplican en runtime (el engine corta antes). UX: leave editables.

        setResultConverter { btn -> if (btn == ButtonType.OK) buildResult() else null }
    }

    private fun buildResult(): HighlightSettings = HighlightSettings(
        enabled = enabledCheck.isSelected,
        promptDetectionEnabled = promptCheck.isSelected,
        keywordsEnabled = keywordsCheck.isSelected,
        commandsEnabled = commandsCheck.isSelected,
        skipOnAlternateScreen = skipAltCheck.isSelected,
        customRules = customRules.toList(),
    )

    private fun configureRulesTable() {
        val enabledCol = TableColumn<CustomHighlightRule, String>("On").apply {
            setCellValueFactory { SimpleStringProperty(if (it.value.enabled) "✓" else "—") }
            prefWidth = 40.0
        }
        val nameCol = TableColumn<CustomHighlightRule, String>("Nombre").apply {
            setCellValueFactory { SimpleStringProperty(it.value.name) }
            prefWidth = 140.0
        }
        val patternCol = TableColumn<CustomHighlightRule, String>("Patrón").apply {
            setCellValueFactory { SimpleStringProperty(it.value.pattern) }
            prefWidth = 200.0
        }
        val fgCol = TableColumn<CustomHighlightRule, String>("Color").apply {
            setCellValueFactory { SimpleStringProperty(it.value.fgRgb) }
            prefWidth = 90.0
        }
        val prioCol = TableColumn<CustomHighlightRule, String>("Prio").apply {
            setCellValueFactory { SimpleStringProperty(it.value.priority.toString()) }
            prefWidth = 60.0
        }
        rulesTable.columns.setAll(enabledCol, nameCol, patternCol, fgCol, prioCol)
        rulesTable.placeholder = Label("Sin reglas custom todavía. Las built-in aplican igual.")
        // Doble-click sobre fila = Editar.
        rulesTable.setOnMouseClicked { e ->
            if (e.clickCount >= 2 && rulesTable.selectionModel.selectedItem != null) editRule()
        }
    }

    private fun refreshTable() {
        rulesTable.items = FXCollections.observableArrayList(customRules)
    }

    private fun addRule() {
        val dialog = HighlightRuleEditorDialog(seed = null)
        dialog.initOwner(dialogPane.scene?.window)
        val newRule = dialog.showAndWait().orElse(null) ?: return
        customRules += newRule
        refreshTable()
        rulesTable.selectionModel.select(newRule)
    }

    private fun editRule() {
        val sel = rulesTable.selectionModel.selectedItem ?: return
        val dialog = HighlightRuleEditorDialog(seed = sel)
        dialog.initOwner(dialogPane.scene?.window)
        val edited = dialog.showAndWait().orElse(null) ?: return
        val idx = customRules.indexOfFirst { it.id == sel.id }
        if (idx >= 0) customRules[idx] = edited
        refreshTable()
        rulesTable.selectionModel.select(edited)
    }

    private fun deleteRule() {
        val sel = rulesTable.selectionModel.selectedItem ?: return
        customRules.removeAll { it.id == sel.id }
        refreshTable()
    }

    private fun importJson() {
        val chooser = FileChooser().apply {
            title = "Importar reglas de resaltado"
            extensionFilters += FileChooser.ExtensionFilter("JSON", "*.json")
        }
        val file = chooser.showOpenDialog(dialogPane.scene?.window) ?: return
        try {
            val imported: List<CustomHighlightRule> = mapper.readValue(file.readText())
            // Reasignamos ids para evitar colisiones con reglas existentes.
            val dedup = imported.map { it.copy(id = java.util.UUID.randomUUID().toString()) }
            customRules.addAll(dedup)
            refreshTable()
        } catch (t: Throwable) {
            errorAlert("Importación fallida", "No se pudo leer ${file.name}: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun exportJson() {
        val chooser = FileChooser().apply {
            title = "Exportar reglas de resaltado"
            extensionFilters += FileChooser.ExtensionFilter("JSON", "*.json")
            initialFileName = "opentermx-highlight-rules.json"
        }
        val file: File = chooser.showSaveDialog(dialogPane.scene?.window) ?: return
        try {
            file.writeText(mapper.writeValueAsString(customRules))
        } catch (t: Throwable) {
            errorAlert("Exportación fallida", "No se pudo escribir ${file.name}: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun errorAlert(title: String, msg: String) {
        Alert(Alert.AlertType.ERROR, msg, ButtonType.OK).apply {
            this.title = title
            this.headerText = title
            initOwner(dialogPane.scene?.window)
        }.showAndWait()
    }
}
