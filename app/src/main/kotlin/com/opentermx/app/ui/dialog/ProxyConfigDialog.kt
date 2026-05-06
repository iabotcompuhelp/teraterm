package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.ProxySettings
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane

class ProxyConfigDialog(initial: ProxySettings) : Dialog<ProxySettings>() {

    private val typeCombo = ComboBox<String>().apply {
        items.addAll("NONE", "HTTP", "SOCKS4", "SOCKS5", "TELNET")
        value = initial.type
    }
    private val hostField = TextField(initial.host)
    private val portSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 65535, initial.port, 1)
        isEditable = true
    }
    private val userField = TextField(initial.username)
    private val passwordField = PasswordField().apply { text = initial.password }

    init {
        title = Strings["setup.proxy.title"]
        headerText = Strings["setup.proxy.header"]
        val grid = GridPane().apply {
            hgap = 10.0; vgap = 8.0; padding = Insets(20.0)
            var r = 0
            add(Label(Strings["setup.proxy.type"]), 0, r); add(typeCombo, 1, r); r++
            add(Label(Strings["setup.proxy.host"]), 0, r); add(hostField, 1, r); r++
            add(Label(Strings["setup.proxy.port"]), 0, r); add(portSpinner, 1, r); r++
            add(Label(Strings["setup.proxy.user"]), 0, r); add(userField, 1, r); r++
            add(Label(Strings["setup.proxy.password"]), 0, r); add(passwordField, 1, r); r++
            add(Label(Strings["setup.proxy.note"]).apply { isWrapText = true; maxWidth = 360.0 },
                0, r, 2, 1)
        }
        dialogPane.content = grid
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        setResultConverter { btn ->
            if (btn != ButtonType.OK) null else ProxySettings(
                type = typeCombo.value,
                host = hostField.text.trim(),
                port = portSpinner.value,
                username = userField.text.trim(),
                password = passwordField.text,
            )
        }
    }
}