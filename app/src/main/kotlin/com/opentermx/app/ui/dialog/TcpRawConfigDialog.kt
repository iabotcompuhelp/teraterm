package com.opentermx.app.ui.dialog

import com.opentermx.common.connection.TcpRawConfig
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane

class TcpRawConfigDialog(initial: TcpRawConfig? = null) : Dialog<TcpRawConfig>() {

    private val hostField = TextField(initial?.host.orEmpty())
    private val portSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 65_535, initial?.port ?: 1234)
        isEditable = true
    }

    init {
        title = "Conexión TCP raw"
        headerText = "Conectar a host:puerto sin negociación"

        hostField.maxWidth = Double.MAX_VALUE

        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 8.0
            padding = Insets(20.0)
            add(Label("Host:"), 0, 0); add(hostField, 1, 0)
            add(Label("Puerto:"), 0, 1); add(portSpinner, 1, 1)
        }
        dialogPane.content = grid

        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        dialogPane.lookupButton(ButtonType.OK).disableProperty().bind(hostField.textProperty().isEmpty)

        setResultConverter { btn ->
            if (btn == ButtonType.OK) {
                TcpRawConfig(host = hostField.text.trim(), port = portSpinner.value ?: 1234)
            } else null
        }
    }
}