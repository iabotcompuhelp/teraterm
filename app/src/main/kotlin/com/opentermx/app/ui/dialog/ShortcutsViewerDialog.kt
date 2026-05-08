package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.AppSettings
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox

/**
 * Read-only table of action → shortcut, used from the Help menu so users can review
 * which keyboard combinations are wired without entering edit mode.
 *
 * `mapping` should be the user's effective accelerators map (with defaults merged in).
 */
class ShortcutsViewerDialog(mapping: Map<String, String>) : Dialog<Void>() {

    init {
        title = Strings["help.shortcuts.title"]
        headerText = Strings["help.shortcuts.header"]

        val table = TableView<Row>().apply {
            val actionCol = TableColumn<Row, String>(Strings["keys.col.action"]).apply {
                setCellValueFactory { SimpleStringProperty(localizeAction(it.value.action)) }
                prefWidth = 320.0
            }
            val keyCol = TableColumn<Row, String>(Strings["keys.col.shortcut"]).apply {
                setCellValueFactory {
                    val v = it.value.shortcut
                    SimpleStringProperty(if (v.isBlank()) Strings["keys.unset"] else v)
                }
                prefWidth = 200.0
            }
            columns.setAll(actionCol, keyCol)
            placeholder = Label(Strings["keys.empty"])
            val rows = AppSettings.DEFAULT_ACCELERATORS.keys
                .union(mapping.keys)
                .map { Row(it, mapping[it].orEmpty().ifBlank { AppSettings.DEFAULT_ACCELERATORS[it].orEmpty() }) }
                .sortedBy { localizeAction(it.action) }
            items = FXCollections.observableArrayList(rows)
        }

        val hint = Label(Strings["help.shortcuts.hint"]).apply {
            isWrapText = true
            maxWidth = 480.0
        }

        val content = VBox(8.0, hint, table).apply {
            padding = Insets(12.0)
            VBox.setVgrow(table, Priority.ALWAYS)
            prefWidth = 560.0
            prefHeight = 420.0
        }

        dialogPane.content = content
        dialogPane.buttonTypes.setAll(ButtonType.CLOSE)

        setResultConverter { null }
    }

    private fun localizeAction(actionKey: String): String =
        runCatching { Strings[actionKey] }.getOrDefault(actionKey)

    private data class Row(val action: String, val shortcut: String)
}