package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.AppSettings
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox

/**
 * Editor of action → accelerator mappings. Returns the new map on OK, null on cancel.
 * The dialog operates on a working copy so the user can preview/cancel safely.
 */
class KeybindingsDialog(initial: Map<String, String>) : Dialog<Map<String, String>?>() {

    // The working copy. Keys are the same identifiers that AppSettings.DEFAULT_ACCELERATORS
    // uses (also valid i18n keys, so we can reuse them as labels).
    private val working: MutableMap<String, String> = LinkedHashMap<String, String>().apply {
        // Always offer every default action; merge user-set values on top.
        AppSettings.DEFAULT_ACCELERATORS.keys.forEach { put(it, "") }
        putAll(initial)
    }

    private val table = TableView<Row>()

    init {
        title = Strings["keys.title"]
        headerText = Strings["keys.header"]

        configureTable()
        refreshRows()

        val edit = Button(Strings["keys.edit"]).apply {
            disableProperty().bind(table.selectionModel.selectedItemProperty().isNull)
            setOnAction { editSelected() }
        }
        val clear = Button(Strings["keys.clear"]).apply {
            disableProperty().bind(table.selectionModel.selectedItemProperty().isNull)
            setOnAction { clearSelected() }
        }
        val resetAll = Button(Strings["keys.resetAll"]).apply { setOnAction { resetAll() } }
        val toolbar = HBox(8.0, edit, clear, resetAll)

        val content = VBox(8.0, table, toolbar).apply {
            padding = Insets(12.0)
            VBox.setVgrow(table, Priority.ALWAYS)
            prefWidth = 540.0
            prefHeight = 380.0
        }
        dialogPane.content = content
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)

        setResultConverter { btn ->
            if (btn == ButtonType.OK) working.toMap() else null
        }

        table.setRowFactory {
            val row = javafx.scene.control.TableRow<Row>()
            row.setOnMouseClicked { ev ->
                if (ev.clickCount == 2 && !row.isEmpty) editRow(row.item)
            }
            row
        }
    }

    private fun configureTable() {
        val actionCol = TableColumn<Row, String>(Strings["keys.col.action"]).apply {
            setCellValueFactory { SimpleStringProperty(localizeAction(it.value.action)) }
            prefWidth = 280.0
        }
        val keyCol = TableColumn<Row, String>(Strings["keys.col.shortcut"]).apply {
            setCellValueFactory {
                SimpleStringProperty(if (it.value.shortcut.isBlank()) Strings["keys.unset"] else it.value.shortcut)
            }
            prefWidth = 220.0
        }
        table.columns.setAll(actionCol, keyCol)
        table.placeholder = Label(Strings["keys.empty"])
    }

    private fun refreshRows() {
        val rows = working.entries.map { Row(it.key, it.value) }
        table.items = FXCollections.observableArrayList(rows)
    }

    private fun editSelected() {
        table.selectionModel.selectedItem?.let { editRow(it) }
    }

    private fun editRow(row: Row) {
        val captured = ShortcutCaptureDialog(localizeAction(row.action), row.shortcut).showAndWait().orElse(null) ?: return
        working[row.action] = captured
        refreshRows()
    }

    private fun clearSelected() {
        val sel = table.selectionModel.selectedItem ?: return
        working[sel.action] = ""
        refreshRows()
    }

    private fun resetAll() {
        val confirm = Alert(Alert.AlertType.CONFIRMATION, Strings["keys.resetConfirm"], ButtonType.OK, ButtonType.CANCEL)
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return
        working.clear()
        working.putAll(AppSettings.DEFAULT_ACCELERATORS)
        refreshRows()
    }

    private fun localizeAction(actionKey: String): String =
        runCatching { Strings[actionKey] }.getOrDefault(actionKey)

    private data class Row(val action: String, val shortcut: String)
}

/**
 * Modal that listens for the next non-modifier key press and reports the resulting
 * KeyCombination in standard "Ctrl+Shift+X" form (parsable by KeyCombination.keyCombination).
 * "" means "no shortcut".
 */
private class ShortcutCaptureDialog(actionLabel: String, currentValue: String) : Dialog<String>() {

    private val preview = Label(if (currentValue.isBlank()) Strings["keys.captureNone"] else currentValue)
    private var captured: String = currentValue

    init {
        title = Strings["keys.captureTitle"]
        headerText = Strings.format("keys.captureHeader", actionLabel)

        val hint = Label(Strings["keys.captureHint"]).apply {
            isWrapText = true
            maxWidth = 360.0
        }
        preview.style = "-fx-font-size: 16px; -fx-font-weight: bold;"

        val content = VBox(10.0, hint, preview).apply { padding = Insets(16.0) }
        dialogPane.content = content
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)

        // Capture key events at the dialog pane: setOnKeyPressed at the scene root sees
        // every press regardless of focus. We consume the event so OK/Cancel buttons
        // don't accidentally trigger on a captured combo.
        dialogPane.setOnKeyPressed { ev ->
            val code: KeyCode = ev.code ?: return@setOnKeyPressed
            if (code.isModifierKey || code == KeyCode.UNDEFINED) return@setOnKeyPressed
            // ESC keeps default behavior (cancel) — don't capture it as a shortcut.
            if (code == KeyCode.ESCAPE) return@setOnKeyPressed

            val mods = mutableListOf<KeyCombination.Modifier>()
            if (ev.isControlDown) mods += KeyCombination.CONTROL_DOWN
            if (ev.isShiftDown) mods += KeyCombination.SHIFT_DOWN
            if (ev.isAltDown) mods += KeyCombination.ALT_DOWN
            if (ev.isMetaDown) mods += KeyCombination.META_DOWN
            if (ev.isShortcutDown && mods.none { it === KeyCombination.SHORTCUT_DOWN }) {
                // Shortcut already covered by Ctrl/Meta on the respective platform.
            }

            val combo = KeyCodeCombination(code, *mods.toTypedArray())
            captured = combo.toString()
            preview.text = captured
            ev.consume()
        }

        setResultConverter { btn -> if (btn == ButtonType.OK) captured else null }
    }
}