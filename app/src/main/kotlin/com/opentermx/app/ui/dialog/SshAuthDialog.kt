package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.SshAuthSettings
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.TextField
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.stage.FileChooser
import java.io.File

class SshAuthDialog(initial: SshAuthSettings) : Dialog<SshAuthSettings>() {

    private val group = ToggleGroup()
    private val passwordRadio = RadioButton(Strings["setup.sshAuth.methodPassword"]).apply {
        toggleGroup = group; isSelected = initial.method == "PASSWORD"
    }
    private val publicKeyRadio = RadioButton(Strings["setup.sshAuth.methodPublicKey"]).apply {
        toggleGroup = group; isSelected = initial.method == "PUBLIC_KEY"
    }
    private val kbdRadio = RadioButton(Strings["setup.sshAuth.methodKbd"]).apply {
        toggleGroup = group; isSelected = initial.method == "KEYBOARD_INTERACTIVE"
    }
    private val keyFileField = TextField(initial.privateKeyPath)
    private val browseButton = Button(Strings["setup.sshAuth.browse"]).apply {
        setOnAction {
            val file: File? = FileChooser().apply {
                title = Strings["setup.sshAuth.browse"]
                if (keyFileField.text.isNotBlank()) {
                    val parent = File(keyFileField.text).parentFile
                    if (parent?.isDirectory == true) initialDirectory = parent
                }
            }.showOpenDialog(dialogPane.scene?.window)
            if (file != null) keyFileField.text = file.absolutePath
        }
    }
    private val defaultUserField = TextField(initial.defaultUsername)
    private val agentFirstCheck = CheckBox(Strings["setup.sshAuth.agentFirst"]).apply {
        isSelected = initial.tryAgentFirst
    }

    init {
        title = Strings["setup.sshAuth.title"]
        headerText = Strings["setup.sshAuth.header"]
        val grid = GridPane().apply {
            hgap = 10.0; vgap = 8.0; padding = Insets(20.0)
            var r = 0
            add(Label(Strings["setup.sshAuth.method"]), 0, r)
            add(HBox(8.0, passwordRadio, publicKeyRadio, kbdRadio), 1, r); r++
            add(Label(Strings["setup.sshAuth.keyFile"]), 0, r)
            add(HBox(6.0, keyFileField, browseButton), 1, r); r++
            add(Label(Strings["setup.sshAuth.defaultUser"]), 0, r); add(defaultUserField, 1, r); r++
            add(agentFirstCheck, 1, r); r++
        }
        dialogPane.content = grid
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        setResultConverter { btn ->
            if (btn != ButtonType.OK) null else SshAuthSettings(
                method = when {
                    publicKeyRadio.isSelected -> "PUBLIC_KEY"
                    kbdRadio.isSelected -> "KEYBOARD_INTERACTIVE"
                    else -> "PASSWORD"
                },
                privateKeyPath = keyFileField.text.trim(),
                defaultUsername = defaultUserField.text.trim(),
                tryAgentFirst = agentFirstCheck.isSelected,
            )
        }
    }
}