package com.opentermx.app.ui.dialog

import com.opentermx.app.ui.rdp.RdpLauncher
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane

/**
 * Dialog para crear/editar una sesión RDP (Microsoft Remote Desktop). El password,
 * cuando se confirma, se persiste cifrado en `SavedConnection.secret` y se inyecta en el
 * Credential Manager de Windows vía [RdpLauncher] al momento de conectar — no en este dialog.
 *
 * El campo "Usuario" acepta `user`, `DOMAIN\user` o `user@domain`. Lo que tipee el operador
 * va literal al `cmdkey /user:...`.
 */
class RdpConfigDialog(
    initialHost: String = "",
    initialPort: Int = RdpLauncher.DEFAULT_RDP_PORT,
    initialLabel: String = "",
    initialUsername: String = "",
    initialPassword: String = "",
    rememberDefault: Boolean = false,
) : Dialog<RdpConfigDialog.Result>() {

    val labelField = TextField(initialLabel).apply {
        promptText = "Etiqueta (ej. Server DC-01)"
    }
    val hostField = TextField(initialHost).apply {
        promptText = "Host o IP (ej. 10.0.0.50 o dc01.lan)"
    }
    val portSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 65_535, initialPort)
        isEditable = true
    }
    val usernameField = TextField(initialUsername).apply {
        promptText = "Usuario (acepta DOMAIN\\user o user@domain)"
    }
    val passwordField = PasswordField().apply {
        text = initialPassword
        promptText = "Password (cifrada en disco, registrada en Credential Manager al conectar)"
    }
    val rememberCheck = CheckBox("Recordar para reconexión rápida desde el panel").apply {
        isSelected = rememberDefault
    }

    init {
        title = "Sesión RDP"
        headerText = "Microsoft Remote Desktop"

        listOf(labelField, hostField, usernameField, passwordField).forEach { it.maxWidth = Double.MAX_VALUE }

        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 8.0
            padding = Insets(20.0)
            add(Label("Etiqueta:"), 0, 0); add(labelField, 1, 0)
            add(Label("Host:"), 0, 1); add(hostField, 1, 1)
            add(Label("Puerto:"), 0, 2); add(portSpinner, 1, 2)
            add(Label("Usuario:"), 0, 3); add(usernameField, 1, 3)
            add(Label("Password:"), 0, 4); add(passwordField, 1, 4)
            add(rememberCheck, 1, 5)
        }
        dialogPane.content = grid
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        dialogPane.lookupButton(ButtonType.OK).disableProperty().bind(hostField.textProperty().isEmpty)

        setResultConverter { btn ->
            if (btn == ButtonType.OK) {
                Result(
                    host = hostField.text.trim(),
                    port = portSpinner.value ?: RdpLauncher.DEFAULT_RDP_PORT,
                    label = labelField.text.trim(),
                    username = usernameField.text.trim(),
                    password = passwordField.text.orEmpty(),
                    remember = rememberCheck.isSelected,
                )
            } else null
        }
    }

    data class Result(
        val host: String,
        val port: Int,
        val label: String,
        val username: String,
        val password: String,
        val remember: Boolean,
    )
}
