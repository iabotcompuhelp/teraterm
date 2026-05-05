package com.opentermx.app.ui.dialog

import com.opentermx.common.connection.SshAuth
import com.opentermx.common.connection.SshConfig
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.control.RadioButton
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.control.TextField
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.stage.FileChooser
import java.io.File

class SshConfigDialog(initial: SshConfig? = null) : Dialog<SshConfig>() {

    private val hostField = TextField()
    private val portSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 65_535, 22)
        isEditable = true
    }
    private val userField = TextField()

    private val authGroup = ToggleGroup()
    private val passwordRadio = RadioButton("Contraseña").apply { toggleGroup = authGroup; isSelected = true }
    private val pubkeyRadio = RadioButton("Clave pública").apply { toggleGroup = authGroup }

    private val passwordField = PasswordField()
    private val keyPathField = TextField()
    private val browseButton = Button("Buscar…")
    private val passphraseField = PasswordField()

    private val keepAliveSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(0, 3600, 60)
        isEditable = true
    }

    private val agentForwardingCheck = CheckBox("Reenvío de agente SSH")

    init {
        title = "Configuración SSH"
        headerText = "Configurar conexión SSH2"

        applyInitial(initial)

        passwordRadio.selectedProperty().addListener { _, _, _ -> updateFieldStates() }
        pubkeyRadio.selectedProperty().addListener { _, _, _ -> updateFieldStates() }
        updateFieldStates()

        browseButton.setOnAction {
            val chooser = FileChooser().apply {
                title = "Seleccionar clave privada SSH"
                val sshDir = File(System.getProperty("user.home"), ".ssh")
                if (sshDir.isDirectory) initialDirectory = sshDir
            }
            chooser.showOpenDialog(dialogPane.scene.window)?.let { keyPathField.text = it.absolutePath }
        }

        val keyPathRow = HBox(6.0, keyPathField, browseButton).also {
            HBox.setHgrow(keyPathField, Priority.ALWAYS)
        }

        listOf(hostField, userField, passwordField, keyPathField, passphraseField).forEach {
            it.maxWidth = Double.MAX_VALUE
        }

        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 8.0
            padding = Insets(20.0)
            add(Label("Host:"), 0, 0); add(hostField, 1, 0)
            add(Label("Puerto:"), 0, 1); add(portSpinner, 1, 1)
            add(Label("Usuario:"), 0, 2); add(userField, 1, 2)
            add(Label("Autenticación:"), 0, 3); add(HBox(12.0, passwordRadio, pubkeyRadio), 1, 3)
            add(Label("Contraseña:"), 0, 4); add(passwordField, 1, 4)
            add(Label("Clave privada:"), 0, 5); add(keyPathRow, 1, 5)
            add(Label("Passphrase:"), 0, 6); add(passphraseField, 1, 6)
            add(Label("Keep-alive (s):"), 0, 7); add(keepAliveSpinner, 1, 7)
            add(agentForwardingCheck, 1, 8)
        }
        dialogPane.content = grid

        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        val okButton = dialogPane.lookupButton(ButtonType.OK)
        okButton.disableProperty().bind(
            hostField.textProperty().isEmpty
                .or(userField.textProperty().isEmpty)
        )

        setResultConverter { btn -> if (btn == ButtonType.OK) buildConfig() else null }
    }

    private fun applyInitial(seed: SshConfig?) {
        if (seed == null) return
        hostField.text = seed.host
        portSpinner.valueFactory.value = seed.port
        userField.text = seed.username
        keepAliveSpinner.valueFactory.value = seed.keepAliveSeconds
        agentForwardingCheck.isSelected = seed.agentForwarding
        when (val auth = seed.auth) {
            is SshAuth.Password -> {
                passwordRadio.isSelected = true
                passwordField.text = String(auth.password)
            }
            is SshAuth.PublicKey -> {
                pubkeyRadio.isSelected = true
                keyPathField.text = auth.privateKeyPath
                auth.passphrase?.let { passphraseField.text = String(it) }
            }
        }
    }

    private fun updateFieldStates() {
        val pwSelected = passwordRadio.isSelected
        passwordField.isDisable = !pwSelected
        keyPathField.isDisable = pwSelected
        browseButton.isDisable = pwSelected
        passphraseField.isDisable = pwSelected
    }

    private fun buildConfig(): SshConfig? {
        val host = hostField.text?.trim().orEmpty()
        val user = userField.text?.trim().orEmpty()
        if (host.isEmpty() || user.isEmpty()) return null

        val auth: SshAuth = if (passwordRadio.isSelected) {
            SshAuth.Password(passwordField.text.orEmpty().toCharArray())
        } else {
            val keyPath = keyPathField.text?.trim().orEmpty()
            if (keyPath.isEmpty()) return null
            val pass = passphraseField.text.orEmpty().takeIf { it.isNotEmpty() }?.toCharArray()
            SshAuth.PublicKey(keyPath, pass)
        }

        return SshConfig(
            host = host,
            username = user,
            auth = auth,
            port = portSpinner.value ?: 22,
            keepAliveSeconds = keepAliveSpinner.value ?: 60,
            agentForwarding = agentForwardingCheck.isSelected,
        )
    }
}