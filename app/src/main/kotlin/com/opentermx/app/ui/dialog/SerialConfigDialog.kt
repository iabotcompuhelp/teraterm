package com.opentermx.app.ui.dialog

import com.opentermx.common.connection.SerialConfig
import com.opentermx.serial.SerialPortDiscovery
import com.opentermx.serial.SerialPortInfo
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.util.StringConverter

class SerialConfigDialog(initial: SerialConfig? = null) : Dialog<SerialConfig>() {

    private val portCombo = ComboBox<SerialPortInfo>()
    private val refreshButton = Button("Refrescar")

    private val baudCombo = ComboBox<Int>().apply {
        items.setAll(300, 1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600)
        isEditable = true
        converter = object : StringConverter<Int>() {
            override fun toString(v: Int?) = v?.toString() ?: ""
            override fun fromString(s: String?) = s?.trim()?.toIntOrNull()
        }
    }
    private val dataBitsCombo = ComboBox<Int>().apply { items.setAll(5, 6, 7, 8) }
    private val stopBitsCombo = ComboBox<SerialConfig.StopBits>()
        .apply { items.setAll(*SerialConfig.StopBits.values()) }
    private val parityCombo = ComboBox<SerialConfig.Parity>()
        .apply { items.setAll(*SerialConfig.Parity.values()) }
    private val flowCombo = ComboBox<SerialConfig.FlowControl>()
        .apply { items.setAll(*SerialConfig.FlowControl.values()) }

    init {
        title = "Configuración serial"
        headerText = "Configurar conexión RS-232"

        portCombo.converter = object : StringConverter<SerialPortInfo>() {
            override fun toString(p: SerialPortInfo?): String =
                if (p == null) "" else "${p.systemPortName()}  —  ${p.descriptivePortName()}"
            override fun fromString(s: String?): SerialPortInfo? = null
        }
        refreshButton.setOnAction { refreshPorts() }
        refreshPorts()

        val seed = initial ?: SerialConfig(portName = "")
        baudCombo.value = seed.baudRate
        dataBitsCombo.value = seed.dataBits
        stopBitsCombo.value = seed.stopBits
        parityCombo.value = seed.parity
        flowCombo.value = seed.flowControl

        val portRow = HBox(6.0, portCombo, refreshButton).also {
            HBox.setHgrow(portCombo, Priority.ALWAYS)
        }
        listOf(portCombo, baudCombo, dataBitsCombo, stopBitsCombo, parityCombo, flowCombo)
            .forEach { it.maxWidth = Double.MAX_VALUE }

        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 8.0
            padding = Insets(20.0)
            add(Label("Puerto:"), 0, 0); add(portRow, 1, 0)
            add(Label("Baudrate:"), 0, 1); add(baudCombo, 1, 1)
            add(Label("Data bits:"), 0, 2); add(dataBitsCombo, 1, 2)
            add(Label("Stop bits:"), 0, 3); add(stopBitsCombo, 1, 3)
            add(Label("Paridad:"), 0, 4); add(parityCombo, 1, 4)
            add(Label("Control de flujo:"), 0, 5); add(flowCombo, 1, 5)
        }
        dialogPane.content = grid

        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        dialogPane.lookupButton(ButtonType.OK).disableProperty()
            .bind(portCombo.valueProperty().isNull)

        setResultConverter { btn -> if (btn == ButtonType.OK) buildConfig() else null }
    }

    private fun refreshPorts() {
        val previous = portCombo.value?.systemPortName()
        portCombo.items.setAll(SerialPortDiscovery.listAvailablePorts())
        portCombo.value = portCombo.items.firstOrNull { it.systemPortName() == previous }
            ?: portCombo.items.firstOrNull()
    }

    private fun buildConfig(): SerialConfig? {
        val port = portCombo.value ?: return null
        return SerialConfig(
            portName = port.systemPortName(),
            baudRate = baudCombo.value ?: 9600,
            dataBits = dataBitsCombo.value ?: 8,
            stopBits = stopBitsCombo.value ?: SerialConfig.StopBits.ONE,
            parity = parityCombo.value ?: SerialConfig.Parity.NONE,
            flowControl = flowCombo.value ?: SerialConfig.FlowControl.NONE,
        )
    }
}