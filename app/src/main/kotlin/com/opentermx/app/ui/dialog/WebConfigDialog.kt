package com.opentermx.app.ui.dialog

import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane

/**
 * Dialog para crear una sesión WEB (admin UI de equipo de red, dashboard vendor, etc.).
 *
 * Devuelve un [Result] al confirmar — el caller arma el `WebSessionView` y la entrada
 * `SavedConnection` correspondiente. El password se descifra solo en memoria al cargar la
 * página (se inyecta vía JS); nunca se persiste en claro.
 */
class WebConfigDialog(
    initialUrl: String = "",
    initialLabel: String = "",
    initialUsername: String = "",
    initialPassword: String = "",
    autofillDefault: Boolean = true,
    rememberDefault: Boolean = false,
) : Dialog<WebConfigDialog.Result>() {

    val labelField = TextField(initialLabel).apply {
        promptText = "Etiqueta (ej. Cisco Switch SW-CORE-01)"
    }
    val urlField = TextField(initialUrl).apply {
        promptText = "https://10.0.0.1/  o  http://router.lab/"
    }
    val usernameField = TextField(initialUsername).apply {
        promptText = "Usuario para auto-fill (opcional)"
    }
    val passwordField = PasswordField().apply {
        text = initialPassword
        promptText = "Password para auto-fill (opcional, cifrada)"
    }
    val autofillCheck = CheckBox("Inyectar credenciales al cargar la página").apply {
        isSelected = autofillDefault
    }
    val rememberCheck = CheckBox("Recordar URL/etiqueta/credenciales para reconexión rápida").apply {
        isSelected = rememberDefault
    }

    init {
        title = "Sesión Web"
        headerText = "Abrir página de administración (Cisco/Aruba/MikroTik/HPE/etc.)"

        listOf(labelField, urlField, usernameField, passwordField).forEach { it.maxWidth = Double.MAX_VALUE }

        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 8.0
            padding = Insets(20.0)
            add(Label("Etiqueta:"), 0, 0); add(labelField, 1, 0)
            add(Label("URL:"), 0, 1); add(urlField, 1, 1)
            add(Label("Usuario:"), 0, 2); add(usernameField, 1, 2)
            add(Label("Password:"), 0, 3); add(passwordField, 1, 3)
            add(autofillCheck, 1, 4)
            add(rememberCheck, 1, 5)
        }
        dialogPane.content = grid
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        dialogPane.lookupButton(ButtonType.OK).disableProperty().bind(urlField.textProperty().isEmpty)

        setResultConverter { btn ->
            if (btn == ButtonType.OK) {
                Result(
                    url = urlField.text.trim(),
                    label = labelField.text.trim(),
                    username = usernameField.text.trim(),
                    password = passwordField.text.orEmpty(),
                    autofill = autofillCheck.isSelected,
                    remember = rememberCheck.isSelected,
                )
            } else null
        }
    }

    data class Result(
        val url: String,
        val label: String,
        val username: String,
        val password: String,
        val autofill: Boolean,
        val remember: Boolean,
    )
}
