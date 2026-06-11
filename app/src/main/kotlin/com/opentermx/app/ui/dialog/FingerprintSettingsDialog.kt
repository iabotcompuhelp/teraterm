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
 * `Setup → Fingerprint de dispositivos…` (Fase 5/6B): edita [FingerprintSettings] y el
 * onboarding al conectar — auto-fingerprint, TTL, pruebas activas, dry-run, y el banner
 * que ofrece agregar al inventario equipos no conocidos. Mismo patrón Dialog<T>.
 */
class FingerprintSettingsDialog(
    initial: FingerprintSettings,
    initialOnboarding: com.opentermx.app.settings.OnboardingSettings = com.opentermx.app.settings.OnboardingSettings(),
) : Dialog<FingerprintSettingsDialog.Result>() {

    data class Result(
        val fingerprint: FingerprintSettings,
        val onboarding: com.opentermx.app.settings.OnboardingSettings,
    )

    private val onboardingCheck = CheckBox(Strings["setup.fingerprint.onboarding"]).apply {
        isSelected = initialOnboarding.askOnConnect
    }
    private val initialOnboarding = initialOnboarding

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
            add(hint("setup.fingerprint.dryRun.hint"), 0, r, 2, 1); r++
            add(javafx.scene.control.Separator(), 0, r, 2, 1); r++
            add(onboardingCheck, 0, r, 2, 1); r++
            add(hint("setup.fingerprint.onboarding.hint"), 0, r, 2, 1)
        }
        dialogPane.content = grid
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        setResultConverter { btn ->
            if (btn != ButtonType.OK) null else Result(
                fingerprint = FingerprintSettings(
                    dryRun = dryRunCheck.isSelected,
                    activeProbing = activeProbingCheck.isSelected,
                    autoOnConnect = autoOnConnectCheck.isSelected,
                    ttlDays = ttlSpinner.value ?: initial.ttlDays,
                ),
                // Conserva la lista de hosts ignorados; solo togglea el ask global.
                onboarding = initialOnboarding.copy(askOnConnect = onboardingCheck.isSelected),
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
