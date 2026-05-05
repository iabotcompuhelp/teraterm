package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.common.connection.PortForward
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.control.TextField
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox

internal class AddForwardDialog(seed: PortForward? = null) : Dialog<PortForward>() {
    private val group = ToggleGroup()
    private val localRadio = RadioButton(Strings["pf.local"]).apply { toggleGroup = group }
    private val remoteRadio = RadioButton(Strings["pf.remote"]).apply { toggleGroup = group }
    private val bindField = TextField()
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

        if (seed == null || seed.direction == PortForward.Direction.LOCAL) {
            localRadio.isSelected = true
        } else {
            remoteRadio.isSelected = true
        }
        bindField.text = seed?.bindAddress ?: "127.0.0.1"
        bindPortSpinner.valueFactory.value = seed?.bindPort ?: 0
        targetHostField.text = seed?.targetHost ?: ""
        targetPortSpinner.valueFactory.value = seed?.targetPort ?: 22

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