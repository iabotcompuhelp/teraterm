package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.FingerprintSettings
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.layout.GridPane

/**
 * `Setup → Fingerprint de dispositivos…` (Fase 5): edita [FingerprintSettings] —
 * auto-fingerprint al conectar, TTL del caché por dispositivo, pruebas activas de rol
 * y dry-run. Mismo patrón Dialog<T> + result converter que Proxy/Keyboard.
 */
class FingerprintSettingsDialog(initial: FingerprintSettings) : Dialog<FingerprintSettings>() {

    private val autoOnConnectCheck = CheckBox(Strings["setup.fingerprint.autoOnConnect"]).apply {
        isSelected = initial.autoOnConnect
    }
    private val ttlSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 365, initial.ttlDays, 1)
        isEditable = true
        disableProperty().bind(autoOnConnectCheck.selectedProperty().not())
    }
    private val activeProbingCheck = CheckBox(Strings["setup.fingerprint.activeProbing"]).apply {
        isSelected = initial.activeProbing
    }
    private val dryRunCheck = CheckBox(Strings["setup.fingerprint.dryRun"]).apply {
        isSelected = initial.dryRun
    }

    init {
        title = Strings["setup.fingerprint.title"]
        headerText = Strings["setup.fingerprint.header"]
        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 6.0
            padding = Insets(20.0)
            var r = 0
            add(autoOnConnectCheck, 0, r, 2, 1); r++
            add(hint("setup.fingerprint.autoOnConnect.hint"), 0, r, 2, 1); r++
            add(Label(Strings["setup.fingerprint.ttlDays"] + ":"), 0, r); add(ttlSpinner, 1, r); r++
            add(hint("setup.fingerprint.ttlDays.hint"), 0, r, 2, 1); r++
            add(activeProbingCheck, 0, r, 2, 1); r++
            add(hint("setup.fingerprint.activeProbing.hint"), 0, r, 2, 1); r++
            add(dryRunCheck, 0, r, 2, 1); r++
            add(hint("setup.fingerprint.dryRun.hint"), 0, r, 2, 1)
        }
        dialogPane.content = grid
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        setResultConverter { btn ->
            if (btn != ButtonType.OK) null else FingerprintSettings(
                dryRun = dryRunCheck.isSelected,
                activeProbing = activeProbingCheck.isSelected,
                autoOnConnect = autoOnConnectCheck.isSelected,
                ttlDays = ttlSpinner.value ?: initial.ttlDays,
            )
        }
    }

    private fun hint(key: String) = Label(Strings[key]).apply {
        isWrapText = true
        maxWidth = 420.0
        styleClass += "hint-label"
        opacity = 0.75
    }
}
