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
import javafx.scene.control.RadioButton
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.GridPane
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
            showError(Strings.format("pf.listError", e.message ?: ""))
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
            showError(Strings.format("pf.removeError", e.message ?: ""))
        }
    }

    private fun showAddDialog() {
        val rule = AddForwardDialog().showAndWait().orElse(null) ?: return
        try {
            val allocated = connection.addPortForward(rule)
            if (rule.direction == PortForward.Direction.LOCAL && rule.bindPort == 0 && allocated != 0) {
                showInfo(Strings.format("pf.addedDynamic", allocated))
            }
            refresh()
        } catch (e: Exception) {
            log.warn("addPortForward falló", e)
            showError(Strings.format("pf.addError", e.message ?: ""))
        }
    }

    private fun confirm(message: String): Boolean {
        val alert = Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL)
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK
    }

    private fun showError(message: String) {
        Alert(Alert.AlertType.ERROR, message, ButtonType.OK).showAndWait()
    }

    private fun showInfo(message: String) {
        Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK).showAndWait()
    }
}

private class AddForwardDialog : Dialog<PortForward>() {
    private val group = ToggleGroup()
    private val localRadio = RadioButton(Strings["pf.local"]).apply { toggleGroup = group; isSelected = true }
    private val remoteRadio = RadioButton(Strings["pf.remote"]).apply { toggleGroup = group }
    private val bindField = TextField("127.0.0.1")
    private val bindPortSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(0, 65_535, 0)
        isEditable = true
    }
    private val targetHostField = TextField()
    private val targetPortSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 65_535, 22)
        isEditable = true
    }

    init {
        title = Strings["pf.addTitle"]
        headerText = Strings["pf.addHeader"]

        listOf(bindField, targetHostField).forEach { it.maxWidth = Double.MAX_VALUE }

        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 8.0
            padding = Insets(20.0)
            add(Label(Strings["pf.direction"]), 0, 0)
            add(HBox(12.0, localRadio, remoteRadio), 1, 0)
            add(Label(Strings["pf.bindAddress"]), 0, 1); add(bindField, 1, 1)
            add(Label(Strings["pf.bindPort"]), 0, 2); add(bindPortSpinner, 1, 2)
            add(Label(Strings["pf.targetHost"]), 0, 3); add(targetHostField, 1, 3)
            add(Label(Strings["pf.targetPort"]), 0, 4); add(targetPortSpinner, 1, 4)
            add(Label(Strings["pf.bindPortHint"]).apply { isWrapText = true }, 1, 5)
        }
        dialogPane.content = grid
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        val okButton = dialogPane.lookupButton(ButtonType.OK)
        okButton.disableProperty().bind(targetHostField.textProperty().isEmpty)

        setResultConverter { btn -> if (btn == ButtonType.OK) buildRule() else null }
    }

    private fun buildRule(): PortForward? {
        val target = targetHostField.text?.trim().orEmpty()
        if (target.isEmpty()) return null
        val direction = if (localRadio.isSelected) PortForward.Direction.LOCAL else PortForward.Direction.REMOTE
        return PortForward(
            direction = direction,
            bindAddress = bindField.text?.trim().orEmpty(),
            bindPort = bindPortSpinner.value ?: 0,
            targetHost = target,
            targetPort = targetPortSpinner.value ?: 22,
        )
    }
}