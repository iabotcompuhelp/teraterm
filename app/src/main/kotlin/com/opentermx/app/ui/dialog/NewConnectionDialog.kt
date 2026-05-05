package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.serial.SerialPortDiscovery
import com.opentermx.serial.SerialPortInfo
import javafx.geometry.Insets
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.util.StringConverter

sealed interface NewConnectionChoice {
    data class Serial(val port: SerialPortInfo) : NewConnectionChoice
    data class Ssh(val host: String, val tcpPort: Int, val sshVersion: SshVersion, val ipVersion: IpVersion) : NewConnectionChoice
    data class Telnet(val host: String, val tcpPort: Int, val ipVersion: IpVersion) : NewConnectionChoice
    data class TcpRaw(val host: String, val tcpPort: Int, val ipVersion: IpVersion) : NewConnectionChoice
}

enum class SshVersion { SSH1, SSH2 }
enum class IpVersion { AUTO, IPV4, IPV6 }

private const val DEFAULT_TELNET_PORT = 23
private const val DEFAULT_SSH_PORT = 22

/**
 * Tera Term-style "New connection" dialog: minimal, fixed layout, just enough
 * to choose protocol + endpoint. Detailed parameters (auth, baudrate, port
 * forwards, etc.) live in the Setup → ... dialogs.
 */
class NewConnectionDialog(
    recentHosts: List<String> = emptyList(),
    historyEnabled: Boolean = true,
) : Dialog<NewConnectionChoice>() {

    private val protocolGroup = ToggleGroup()
    private val tcpRadio = RadioButton("TCP/IP").apply { toggleGroup = protocolGroup; isSelected = true }
    private val serialRadio = RadioButton("Serial").apply { toggleGroup = protocolGroup }

    // ---- TCP/IP side ----
    private val hostCombo = ComboBox<String>().apply {
        items.setAll(recentHosts)
        isEditable = true
        prefWidth = 240.0
    }
    private val historyCheck = CheckBox(Strings["nc.history"]).apply { isSelected = historyEnabled }

    private val serviceGroup = ToggleGroup()
    private val telnetRadio = RadioButton("Telnet").apply { toggleGroup = serviceGroup }
    private val sshRadio = RadioButton("SSH").apply { toggleGroup = serviceGroup; isSelected = true }
    private val otherRadio = RadioButton(Strings["nc.other"]).apply { toggleGroup = serviceGroup }

    private val portSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 65_535, DEFAULT_SSH_PORT)
        isEditable = true
        prefWidth = 90.0
    }

    private val sshVersionCombo = ComboBox<SshVersion>().apply {
        items.setAll(SshVersion.SSH2, SshVersion.SSH1)
        value = SshVersion.SSH2
    }
    private val sshVersionLabel = Label(Strings["nc.sshVersion"])

    private val ipVersionCombo = ComboBox<IpVersion>().apply {
        items.setAll(IpVersion.AUTO, IpVersion.IPV4, IpVersion.IPV6)
        value = IpVersion.AUTO
    }

    // ---- Serial side ----
    private val serialPortCombo = ComboBox<SerialPortInfo>().apply {
        items.setAll(SerialPortDiscovery.listAvailablePorts())
        if (items.isNotEmpty()) value = items.first()
        prefWidth = 320.0
        converter = object : StringConverter<SerialPortInfo>() {
            override fun toString(p: SerialPortInfo?) = p?.let { "${it.systemPortName}: ${it.descriptivePortName}" } ?: ""
            override fun fromString(s: String?) = null
        }
    }

    init {
        title = "OpenTermX: " + Strings["nc.title"]
        isResizable = false

        // Wire reactivity: protocol → enable side, service → port autofill + ssh visibility
        protocolGroup.selectedToggleProperty().addListener { _, _, _ -> updateProtocolState() }
        serviceGroup.selectedToggleProperty().addListener { _, _, _ -> updateServiceState() }

        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 8.0
            padding = Insets(16.0)

            // Row 0: TCP/IP radio
            add(tcpRadio, 0, 0)

            // TCP/IP block
            add(Label("Host:"), 1, 1); add(hostCombo, 2, 1); add(historyCheck, 3, 1)
            add(Label(Strings["nc.service"]), 1, 2)
            add(HBox(10.0, telnetRadio, sshRadio, otherRadio), 2, 2, 2, 1)
            add(Label(Strings["nc.tcpPort"]), 1, 3); add(portSpinner, 2, 3)
            add(sshVersionLabel, 1, 4); add(sshVersionCombo, 2, 4)
            add(Label(Strings["nc.ipVersion"]), 1, 5); add(ipVersionCombo, 2, 5)

            // Row 6: Serial radio + port dropdown
            add(serialRadio, 0, 6)
            add(Label("Port:"), 1, 7); add(serialPortCombo, 2, 7, 2, 1)
        }
        dialogPane.content = grid

        val helpType = ButtonType(Strings["nc.help"], ButtonBar.ButtonData.HELP)
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL, helpType)
        // Custom HELP button closes the dialog by default. Intercept to show help instead.
        val helpButton = dialogPane.lookupButton(helpType)
        helpButton?.addEventFilter(javafx.event.ActionEvent.ACTION) { ev ->
            ev.consume()
            javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.INFORMATION,
                Strings["nc.helpBody"],
                ButtonType.OK
            ).apply {
                title = Strings["nc.helpTitle"]
                headerText = Strings["nc.helpTitle"]
            }.showAndWait()
        }

        updateProtocolState()
        updateServiceState()

        setResultConverter { btn -> if (btn == ButtonType.OK) buildChoice() else null }
    }

    private fun updateProtocolState() {
        val tcp = tcpRadio.isSelected
        listOf<javafx.scene.control.Control>(
            hostCombo, historyCheck, telnetRadio, sshRadio, otherRadio,
            portSpinner, sshVersionCombo, ipVersionCombo
        ).forEach { it.isDisable = !tcp }
        sshVersionLabel.isDisable = !tcp
        serialPortCombo.isDisable = tcp
    }

    private fun updateServiceState() {
        val isSsh = sshRadio.isSelected
        sshVersionCombo.isVisible = isSsh
        sshVersionLabel.isVisible = isSsh
        portSpinner.valueFactory.value = when {
            telnetRadio.isSelected -> DEFAULT_TELNET_PORT
            sshRadio.isSelected -> DEFAULT_SSH_PORT
            else -> portSpinner.value ?: DEFAULT_SSH_PORT
        }
    }

    private fun buildChoice(): NewConnectionChoice? {
        if (serialRadio.isSelected) {
            val port = serialPortCombo.value ?: return null
            return NewConnectionChoice.Serial(port)
        }
        val host = hostCombo.editor.text?.trim().orEmpty().ifEmpty { hostCombo.value?.trim().orEmpty() }
        if (host.isEmpty()) return null
        val tcpPort = portSpinner.value ?: DEFAULT_SSH_PORT
        val ipv = ipVersionCombo.value ?: IpVersion.AUTO
        return when {
            telnetRadio.isSelected -> NewConnectionChoice.Telnet(host, tcpPort, ipv)
            sshRadio.isSelected -> NewConnectionChoice.Ssh(host, tcpPort, sshVersionCombo.value ?: SshVersion.SSH2, ipv)
            else -> NewConnectionChoice.TcpRaw(host, tcpPort, ipv)
        }
    }

    fun isHistoryEnabled(): Boolean = historyCheck.isSelected
}