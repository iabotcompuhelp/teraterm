package com.opentermx.app.ui

import com.opentermx.app.settings.SavedConnection
import javafx.collections.FXCollections
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.MenuItem
import javafx.scene.input.KeyCode
import javafx.scene.layout.BorderPane
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox

/**
 * Listado vertical de las conexiones recordadas (`SavedConnection`). Vive en la mitad
 * inferior del SplitPane lateral del [MainWindow]. Doble-click sobre una entrada dispara
 * [onQuickConnect]; clic-derecho ofrece "Conectar" y "Eliminar" (con confirmación); la
 * tecla `DELETE` también borra la entrada seleccionada.
 *
 * El contenido se refresca llamando [refresh] cada vez que `AppSettings.savedConnections`
 * muta (en `MainWindow.persist { ... }` tras alta/edición/borrado).
 */
class SavedConnectionsListView(
    private val savedProvider: () -> List<SavedConnection>,
    private val onQuickConnect: (SavedConnection) -> Unit,
    private val onDelete: (SavedConnection) -> Unit,
) : BorderPane() {

    private val list = ListView<SavedConnection>(FXCollections.observableArrayList()).apply {
        styleClass += "saved-connections-list"
        cellFactory = javafx.util.Callback {
            object : ListCell<SavedConnection>() {
                override fun updateItem(item: SavedConnection?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else formatCell(item)
                }
            }
        }
        placeholder = Label("Sin conexiones guardadas todavía.")
        setOnMouseClicked { e ->
            if (e.clickCount >= 2) {
                selectionModel.selectedItem?.let { onQuickConnect(it) }
            }
        }
        setOnKeyPressed { e ->
            if (e.code == KeyCode.DELETE) {
                selectionModel.selectedItem?.let { requestDelete(it) }
            } else if (e.code == KeyCode.ENTER) {
                selectionModel.selectedItem?.let { onQuickConnect(it) }
            }
        }
        contextMenu = ContextMenu(
            MenuItem("Conectar").apply {
                setOnAction { selectionModel.selectedItem?.let { onQuickConnect(it) } }
            },
            MenuItem("Eliminar…").apply {
                setOnAction { selectionModel.selectedItem?.let { requestDelete(it) } }
            },
        )
    }

    init {
        styleClass += "saved-connections-pane"
        // VBox + Vgrow.ALWAYS fuerza al list a expandirse al alto disponible. Sin esto,
        // dentro de un SplitPane con muchas entradas, el ListView puede no virtualizar
        // correctamente y el scrollbar no aparece.
        val header = Label("Conexiones guardadas").apply { styleClass += "panel-header" }
        val column = VBox(header, list).apply {
            VBox.setVgrow(list, Priority.ALWAYS)
        }
        center = column
        refresh()
    }

    /**
     * Pide confirmación antes de borrar — la entrada lleva passwords cifradas, no queremos
     * que un Delete mal apuntado borre credenciales sin warning.
     */
    private fun requestDelete(entry: SavedConnection) {
        val alert = Alert(Alert.AlertType.CONFIRMATION).apply {
            title = "Eliminar conexión guardada"
            headerText = "Eliminar \"${entry.displayLabel()}\""
            contentText = "Se borrarán también las credenciales asociadas. ¿Continuar?"
            buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        }
        scene?.window?.let { alert.initOwner(it) }
        val result = alert.showAndWait().orElse(ButtonType.CANCEL)
        if (result == ButtonType.OK) onDelete(entry)
    }

    fun refresh() {
        val sorted = savedProvider().sortedByDescending { it.lastUsedAtMillis }
        list.items.setAll(sorted)
    }

    /**
     * Formato de la celda: `[SSH] Router Core (admin@10.0.0.1)` o `[TELNET] Switch L3 (10.0.0.5:23)`
     * según el protocolo y si el operador definió un label. Doble-click conecta inmediatamente.
     */
    private fun formatCell(s: SavedConnection): String {
        val protoTag = "[${s.protocol}]"
        val endpoint = if (s.username.isNotBlank()) "${s.username}@${s.host}:${s.port}" else "${s.host}:${s.port}"
        return if (s.label.isNotBlank()) "$protoTag ${s.label} ($endpoint)" else "$protoTag $endpoint"
    }
}
