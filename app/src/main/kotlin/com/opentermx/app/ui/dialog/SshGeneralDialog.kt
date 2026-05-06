package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.SshGeneralSettings
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.control.TextArea
import javafx.scene.layout.GridPane

class SshGeneralDialog(initial: SshGeneralSettings) : Dialog<SshGeneralSettings>() {

    private val compressionCheck = CheckBox().apply { isSelected = initial.compression }
    private val ciphersArea = listArea(initial.ciphers)
    private val kexArea = listArea(initial.kex)
    private val macsArea = listArea(initial.macs)
    private val heartbeatSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(0, 3600, initial.heartbeatSeconds, 5)
        isEditable = true
    }

    init {
        title = Strings["setup.sshGen.title"]
        headerText = Strings["setup.sshGen.header"]
        val grid = GridPane().apply {
            hgap = 10.0; vgap = 8.0; padding = Insets(20.0)
            var r = 0
            add(Label(Strings["setup.sshGen.compression"]), 0, r); add(compressionCheck, 1, r); r++
            add(Label(Strings["setup.sshGen.ciphers"]), 0, r); add(ciphersArea, 1, r); r++
            add(Label(Strings["setup.sshGen.kex"]), 0, r); add(kexArea, 1, r); r++
            add(Label(Strings["setup.sshGen.macs"]), 0, r); add(macsArea, 1, r); r++
            add(Label(Strings["setup.sshGen.heartbeat"]), 0, r); add(heartbeatSpinner, 1, r); r++
            add(Label(Strings["setup.sshGen.note"]).apply { isWrapText = true; maxWidth = 360.0 },
                0, r, 2, 1)
        }
        dialogPane.content = grid
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        setResultConverter { btn ->
            if (btn != ButtonType.OK) null else SshGeneralSettings(
                compression = compressionCheck.isSelected,
                ciphers = parseLines(ciphersArea.text),
                kex = parseLines(kexArea.text),
                macs = parseLines(macsArea.text),
                heartbeatSeconds = heartbeatSpinner.value,
            )
        }
    }

    private fun listArea(values: List<String>) = TextArea(values.joinToString("\n")).apply {
        prefRowCount = 4; prefColumnCount = 30
    }

    private fun parseLines(s: String): List<String> =
        s.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
}