package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.common.connection.PortForward
import com.opentermx.common.connection.SshAuth
import com.opentermx.common.connection.SshConfig
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
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
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import java.io.File

class SshConfigDialog(
    initial: SshConfig? = null,
    rememberCredentialsDefault: Boolean = false,
    initialLabel: String = "",
) : Dialog<SshConfig>() {

    private val hostField = TextField()
    private val portSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 65_535, 22)
        isEditable = true
    }
    private val userField = TextField()

    /**
     * Etiqueta amigable para el equipo (ej. "Router Core"). Se persiste en `SavedConnection.label`
     * cuando "Recordar credenciales" está tildado y se usa como título del tab si no está vacía.
     * Público para que `MainWindow` lo lea tras `showAndWait()`.
     */
    val labelField = TextField(initialLabel)

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

    /**
     * Si está tildado al cerrar con OK, `MainWindow` persiste estas credenciales en
     * `AppSettings.savedConnections` (password/passphrase cifradas con SecretCipher).
     * Si está destildado y había una entrada saved previa para `(host, port, user)`,
     * el caller la elimina. Default viene del constructor — `true` cuando el seed
     * salió del propio keychain (autofill), `false` para conexiones manuales.
     */
    val rememberCredentialsCheck = CheckBox("Recordar credenciales (cifradas localmente)").apply {
        isSelected = rememberCredentialsDefault
    }

    private var initialForwards: List<PortForward> = emptyList()
    private val forwardsButton = Button()

    // Fields the dialog does not render — captured from the seed and propagated
    // unchanged so global Setup → SSH / SSH Authentication settings survive a roundtrip.
    private var seedTryAgentFirst: Boolean = false
    private var seedCompression: Boolean = false
    private var seedCiphers: List<String> = emptyList()
    private var seedKex: List<String> = emptyList()
    private var seedMacs: List<String> = emptyList()
    private var seedTerminalType: String = "xterm-256color"

    init {
        title = "Configuración SSH"
        headerText = "Configurar conexión SSH2"

        applyInitial(initial)
        updateForwardsButtonLabel()
        forwardsButton.setOnAction { editInitialForwards() }

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

        listOf(hostField, userField, passwordField, keyPathField, passphraseField, labelField).forEach {
            it.maxWidth = Double.MAX_VALUE
        }
        labelField.promptText = "Etiqueta opcional (ej. Router Core)"

        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 8.0
            padding = Insets(20.0)
            add(Label("Etiqueta:"), 0, 0); add(labelField, 1, 0)
            add(Label("Host:"), 0, 1); add(hostField, 1, 1)
            add(Label("Puerto:"), 0, 2); add(portSpinner, 1, 2)
            add(Label("Usuario:"), 0, 3); add(userField, 1, 3)
            add(Label("Autenticación:"), 0, 4); add(HBox(12.0, passwordRadio, pubkeyRadio), 1, 4)
            add(Label("Contraseña:"), 0, 5); add(passwordField, 1, 5)
            add(Label("Clave privada:"), 0, 6); add(keyPathRow, 1, 6)
            add(Label("Passphrase:"), 0, 7); add(passphraseField, 1, 7)
            add(Label("Keep-alive (s):"), 0, 8); add(keepAliveSpinner, 1, 8)
            add(agentForwardingCheck, 1, 9)
            add(forwardsButton, 1, 10)
            add(rememberCredentialsCheck, 1, 11)
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
        initialForwards = seed.portForwards.toList()
        seedTryAgentFirst = seed.tryAgentFirst
        seedCompression = seed.compression
        seedCiphers = seed.ciphers
        seedKex = seed.kex
        seedMacs = seed.macs
        seedTerminalType = seed.terminalType
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

    private fun updateForwardsButtonLabel() {
        forwardsButton.text = Strings.format("ssh.forwardsButton", initialForwards.size)
    }

    private fun editInitialForwards() {
        val edited = InitialForwardsDialog(initialForwards).showAndWait().orElse(null) ?: return
        initialForwards = edited
        updateForwardsButtonLabel()
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
            tryAgentFirst = seedTryAgentFirst,
            portForwards = initialForwards,
            compression = seedCompression,
            ciphers = seedCiphers,
            kex = seedKex,
            macs = seedMacs,
            terminalType = seedTerminalType,
        )
    }
}

private class InitialForwardsDialog(initial: List<PortForward>) : Dialog<List<PortForward>>() {

    private val rules: MutableList<PortForward> = initial.toMutableList()
    private val table = TableView<PortForward>()

    init {
        title = Strings["ssh.forwardsTitle"]
        headerText = Strings["ssh.forwardsHeader"]

        configureTable()
        refresh()

        val addBtn = Button(Strings["pf.add"]).apply { setOnAction { add() } }
        val removeBtn = Button(Strings["pf.remove"]).apply {
            disableProperty().bind(table.selectionModel.selectedItemProperty().isNull)
            setOnAction { remove() }
        }
        val toolbar = HBox(8.0, addBtn, removeBtn)

        val content = VBox(8.0, table, toolbar).apply {
            padding = Insets(12.0)
            VBox.setVgrow(table, Priority.ALWAYS)
            prefWidth = 520.0
            prefHeight = 280.0
        }
        dialogPane.content = content
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        setResultConverter { btn -> if (btn == ButtonType.OK) rules.toList() else null }
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
            prefWidth = 70.0
        }
        val targetCol = TableColumn<PortForward, String>(Strings["pf.col.target"]).apply {
            setCellValueFactory { SimpleStringProperty(it.value.targetHost) }
            prefWidth = 160.0
        }
        val targetPortCol = TableColumn<PortForward, String>(Strings["pf.col.targetPort"]).apply {
            setCellValueFactory { SimpleStringProperty(it.value.targetPort.toString()) }
            prefWidth = 70.0
        }
        table.columns.setAll(dirCol, bindCol, bindPortCol, targetCol, targetPortCol)
        table.placeholder = Label(Strings["pf.empty"])
    }

    private fun refresh() {
        table.items = FXCollections.observableArrayList(rules)
    }

    private fun add() {
        val rule = AddForwardDialog().showAndWait().orElse(null) ?: return
        rules += rule
        refresh()
    }

    private fun remove() {
        val sel = table.selectionModel.selectedItem ?: return
        rules.remove(sel)
        refresh()
    }
}