package com.opentermx.app.ui.dialog

import com.opentermx.app.settings.SavedAuthKind
import com.opentermx.app.settings.SavedConnection
import com.opentermx.app.settings.SavedConnections
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Listado de las credenciales SSH recordadas. Permite eliminar entradas individuales o todas.
 * Devuelve la lista actualizada al cerrar con OK (o `null` si Cancel).
 *
 * No expone los secretos: solo muestra (usuario, host, puerto, tipo de auth, última vez usada).
 */
class SavedConnectionsDialog(initial: List<SavedConnection>) : Dialog<List<SavedConnection>>() {

    private var current: List<SavedConnection> = initial.sortedByDescending { it.lastUsedAtMillis }
    private val table = TableView<SavedConnection>()
    private val deleteAllButton = Button("Eliminar todo")

    init {
        title = "Conexiones guardadas"
        headerText = "Credenciales SSH recordadas (passwords/passphrases cifradas localmente)"

        configureTable()
        refresh()

        val deleteSelected = Button("Eliminar selección").apply {
            disableProperty().bind(table.selectionModel.selectedItemProperty().isNull)
            setOnAction { deleteSelection() }
        }
        deleteAllButton.setOnAction { deleteAll() }
        val toolbar = HBox(8.0, deleteSelected, deleteAllButton)

        val content = VBox(8.0, table, toolbar).apply {
            padding = Insets(12.0)
            VBox.setVgrow(table, Priority.ALWAYS)
            prefWidth = 620.0
            prefHeight = 320.0
        }
        dialogPane.content = content
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        setResultConverter { btn -> if (btn == ButtonType.OK) current else null }
    }

    private fun configureTable() {
        val userCol = TableColumn<SavedConnection, String>("Usuario").apply {
            setCellValueFactory { SimpleStringProperty(it.value.username) }
            prefWidth = 120.0
        }
        val hostCol = TableColumn<SavedConnection, String>("Host").apply {
            setCellValueFactory { SimpleStringProperty(it.value.host) }
            prefWidth = 200.0
        }
        val portCol = TableColumn<SavedConnection, String>("Puerto").apply {
            setCellValueFactory { SimpleStringProperty(it.value.port.toString()) }
            prefWidth = 60.0
        }
        val authCol = TableColumn<SavedConnection, String>("Auth").apply {
            setCellValueFactory { SimpleStringProperty(authLabel(it.value)) }
            prefWidth = 100.0
        }
        val lastUsedCol = TableColumn<SavedConnection, String>("Última vez").apply {
            setCellValueFactory { SimpleStringProperty(formatTimestamp(it.value.lastUsedAtMillis)) }
            prefWidth = 140.0
        }
        table.columns.setAll(userCol, hostCol, portCol, authCol, lastUsedCol)
        table.placeholder = Label("No hay credenciales recordadas.")
    }

    private fun authLabel(c: SavedConnection): String = when (c.authKind) {
        SavedAuthKind.PASSWORD -> if (c.secret != null) "password (saved)" else "password (no secret)"
        SavedAuthKind.SSH_KEY -> "ssh key" + if (c.secret != null) " + passphrase" else ""
        SavedAuthKind.NONE -> "—"
    }

    private fun formatTimestamp(millis: Long): String {
        if (millis <= 0) return "—"
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        return fmt.format(Instant.ofEpochMilli(millis))
    }

    private fun refresh() {
        table.items = FXCollections.observableArrayList(current)
        deleteAllButton.isDisable = current.isEmpty()
    }

    private fun deleteSelection() {
        val sel = table.selectionModel.selectedItem ?: return
        current = SavedConnections.removeById(current, sel.id)
        refresh()
    }

    private fun deleteAll() {
        current = emptyList()
        refresh()
    }
}
