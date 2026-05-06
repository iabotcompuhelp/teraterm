package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.TcpIpSettings
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane

class TcpIpConfigDialog(initial: TcpIpSettings) : Dialog<TcpIpSettings>() {

    private val keepAliveCheck = CheckBox().apply { isSelected = initial.keepAlive }
    private val keepAliveSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 7200, initial.keepAliveSeconds, 5)
        isEditable = true
    }
    private val recvBufferSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1024, 1_048_576, initial.recvBufferSize, 1024)
        isEditable = true
    }
    private val dnsCombo = ComboBox<String>().apply {
        items.addAll("AUTO", "IPV4", "IPV6"); value = initial.dnsMode
    }
    private val telnetPortSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 65535, initial.telnetPort, 1)
        isEditable = true
    }
    private val termTypeField = TextField(initial.terminalType)

    init {
        title = Strings["setup.tcpip.title"]
        headerText = Strings["setup.tcpip.header"]
        val grid = GridPane().apply {
            hgap = 10.0; vgap = 8.0; padding = Insets(20.0)
            var r = 0
            add(Label(Strings["setup.tcpip.keepAlive"]), 0, r); add(keepAliveCheck, 1, r); r++
            add(Label(Strings["setup.tcpip.keepAliveSeconds"]), 0, r); add(keepAliveSpinner, 1, r); r++
            add(Label(Strings["setup.tcpip.recvBuffer"]), 0, r); add(recvBufferSpinner, 1, r); r++
            add(Label(Strings["setup.tcpip.dns"]), 0, r); add(dnsCombo, 1, r); r++
            add(Label(Strings["setup.tcpip.telnetPort"]), 0, r); add(telnetPortSpinner, 1, r); r++
            add(Label(Strings["setup.tcpip.termType"]), 0, r); add(termTypeField, 1, r); r++
        }
        dialogPane.content = grid
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        setResultConverter { btn ->
            if (btn != ButtonType.OK) null else TcpIpSettings(
                keepAlive = keepAliveCheck.isSelected,
                keepAliveSeconds = keepAliveSpinner.value,
                recvBufferSize = recvBufferSpinner.value,
                dnsMode = dnsCombo.value,
                telnetPort = telnetPortSpinner.value,
                terminalType = termTypeField.text.ifBlank { "xterm-256color" },
            )
        }
    }
}