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

class TelnetConfigDialog(
    initial: TelnetConfig? = null,
    initialLabel: String = "",
    initialUsername: String = "",
    rememberDefault: Boolean = false,
) : Dialog<TelnetConfig>() {

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

    /** Etiqueta amigable. Si no está vacía, MainWindow la usa como nombre de tab. */
    val labelField = TextField(initialLabel).apply {
        promptText = "Etiqueta opcional (ej. Switch Lab 13)"
    }
    /**
     * Usuario solo informativo: Telnet no negocia credenciales en el protocolo. Sirve para
     * recordar con qué login se entra al equipo (login interactivo o auto-login macro).
     */
    val usernameField = TextField(initialUsername).apply {
        promptText = "Usuario para referencia (opcional, no se envía)"
    }
    val rememberCheck = CheckBox("Recordar (etiqueta + host) para reconexión rápida").apply {
        isSelected = rememberDefault
    }

    init {
        title = "Configuración Telnet"
        headerText = "Configurar conexión Telnet"

        listOf(hostField, labelField, usernameField).forEach { it.maxWidth = Double.MAX_VALUE }

        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 8.0
            padding = Insets(20.0)
            add(Label("Etiqueta:"), 0, 0); add(labelField, 1, 0)
            add(Label("Host:"), 0, 1); add(hostField, 1, 1)
            add(Label("Puerto:"), 0, 2); add(portSpinner, 1, 2)
            add(Label("Usuario:"), 0, 3); add(usernameField, 1, 3)
            add(Label("Seguridad:"), 0, 4); add(tlsCheck, 1, 4)
            add(rememberCheck, 1, 5)
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