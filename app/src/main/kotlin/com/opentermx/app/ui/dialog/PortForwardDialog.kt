package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.common.connection.PortForward
import com.opentermx.ssh.SshConnection
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
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import org.slf4j.LoggerFactory

class PortForwardDialog(private val connection: SshConnection) : Dialog<Void?>() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val table = TableView<PortForward>()

    init {
        title = Strings["pf.title"]
        headerText = Strings["pf.header"]

        configureTable()
        refresh()

        val addBtn = Button(Strings["pf.add"]).apply { setOnAction { showAddDialog() } }
        val removeBtn = Button(Strings["pf.remove"]).apply {
            disableProperty().bind(table.selectionModel.selectedItemProperty().isNull)
            setOnAction { removeSelected() }
        }
        val refreshBtn = Button(Strings["pf.refresh"]).apply { setOnAction { refresh() } }
        val actions = HBox(8.0, addBtn, removeBtn, refreshBtn)

        val content = VBox(8.0, table, actions).apply {
            padding = Insets(12.0)
            VBox.setVgrow(table, Priority.ALWAYS)
            prefWidth = 560.0
            prefHeight = 320.0
        }
        dialogPane.content = content
        dialogPane.buttonTypes.setAll(ButtonType.CLOSE)
    }

    private fun configureTable() {
        val dirCol = TableColumn<PortForward, String>(Strings["pf.col.direction"]).apply {
            setCellValueFactory { SimpleStringProperty(it.value.direction.name) }
            prefWidth = 90.0
        }
        val bindCol = TableColumn<PortForward, String>(Strings["pf.col.bind"]).apply {
            setCellValueFactory { SimpleStringProperty(it.value.bindAddress.ifBlank { "*" }) }
            prefWidth = 100.0
        }
        val bindPortCol = TableColumn<PortForward, String>(Strings["pf.col.bindPort"]).apply {
            setCellValueFactory { SimpleStringProperty(it.value.bindPort.toString()) }
            prefWidth = 80.0
        }
        val targetCol = TableColumn<PortForward, String>(Strings["pf.col.target"]).apply {
            setCellValueFactory { SimpleStringProperty(it.value.targetHost) }
            prefWidth = 160.0
        }
        val targetPortCol = TableColumn<PortForward, String>(Strings["pf.col.targetPort"]).apply {
            setCellValueFactory { SimpleStringProperty(it.value.targetPort.toString()) }
            prefWidth = 80.0
        }
        table.columns.setAll(dirCol, bindCol, bindPortCol, targetCol, targetPortCol)
        table.placeholder = Label(Strings["pf.empty"])
    }

    private fun refresh() {
        try {
            table.items = FXCollections.observableArrayList(connection.listPortForwards())
        } catch (e: Exception) {
            log.warn("listPortForwards falló", e)
            ErrorDialog.error(
                owner = dialogPane.scene?.window,
                title = Strings["pf.title"],
                header = Strings["pf.listError.header"],
                message = e.message,
                cause = e,
            )
        }
    }

    private fun removeSelected() {
        val sel = table.selectionModel.selectedItem ?: return
        if (!confirm(Strings.format("pf.removeConfirm", sel.describe()))) return
        try {
            connection.removePortForward(sel)
            refresh()
        } catch (e: Exception) {
            log.warn("removePortForward falló", e)
            ErrorDialog.error(
                owner = dialogPane.scene?.window,
                title = Strings["pf.title"],
                header = Strings["pf.removeError.header"],
                message = e.message,
                cause = e,
            )
        }
    }

    private fun showAddDialog() {
        val rule = AddForwardDialog().showAndWait().orElse(null) ?: return
        try {
            val allocated = connection.addPortForward(rule)
            if (rule.direction == PortForward.Direction.LOCAL && rule.bindPort == 0 && allocated != 0) {
                ErrorDialog.info(
                    owner = dialogPane.scene?.window,
                    title = Strings["pf.title"],
                    header = Strings["pf.addedDynamic.header"],
                    message = Strings.format("pf.addedDynamic", allocated),
                )
            }
            refresh()
        } catch (e: Exception) {
            log.warn("addPortForward falló", e)
            ErrorDialog.error(
                owner = dialogPane.scene?.window,
                title = Strings["pf.title"],
                header = Strings["pf.addError.header"],
                message = e.message,
                cause = e,
            )
        }
    }

    private fun confirm(message: String): Boolean {
        val alert = Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL)
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK
    }
}