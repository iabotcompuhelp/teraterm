package com.opentermx.app.ui.dialog

import com.opentermx.common.connection.TelnetConfig
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane

class TelnetConfigDialog(initial: TelnetConfig? = null) : Dialog<TelnetConfig>() {

    private val hostField = TextField(initial?.host.orEmpty())
    private val portSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 65_535, initial?.port ?: 23)
        isEditable = true
    }
    private val tlsCheck = CheckBox("Usar TLS (telnets)").apply {
        isSelected = initial?.useTls == true
        selectedProperty().addListener { _, _, selected ->
            if (selected && portSpinner.value == 23) portSpinner.valueFactory.value = 992
            if (!selected && portSpinner.value == 992) portSpinner.valueFactory.value = 23
        }
    }

    init {
        title = "Configuración Telnet"
        headerText = "Configurar conexión Telnet"

        hostField.maxWidth = Double.MAX_VALUE

        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 8.0
            padding = Insets(20.0)
            add(Label("Host:"), 0, 0); add(hostField, 1, 0)
            add(Label("Puerto:"), 0, 1); add(portSpinner, 1, 1)
            add(Label("Seguridad:"), 0, 2); add(tlsCheck, 1, 2)
        }
        dialogPane.content = grid

        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        dialogPane.lookupButton(ButtonType.OK).disableProperty().bind(hostField.textProperty().isEmpty)

        setResultConverter { btn ->
            if (btn == ButtonType.OK) {
                TelnetConfig(
                    host = hostField.text.trim(),
                    port = portSpinner.value ?: 23,
                    useTls = tlsCheck.isSelected,
                )
            } else null
        }
    }
}