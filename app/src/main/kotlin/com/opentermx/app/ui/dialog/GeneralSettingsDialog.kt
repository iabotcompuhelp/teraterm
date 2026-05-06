package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.GeneralSettings
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.TextField
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.stage.DirectoryChooser
import java.io.File

data class GeneralResult(val locale: String, val general: GeneralSettings)

class GeneralSettingsDialog(initialLocale: String, initial: GeneralSettings) : Dialog<GeneralResult>() {

    private val langGroup = ToggleGroup()
    private val esRadio = RadioButton(Strings["setup.languageEs"]).apply {
        toggleGroup = langGroup; isSelected = initialLocale == "es"
    }
    private val enRadio = RadioButton(Strings["setup.languageEn"]).apply {
        toggleGroup = langGroup; isSelected = initialLocale == "en"
    }
    private val workingDirField = TextField(initial.workingDirectory)
    private val browseDirButton = Button(Strings["setup.general.browseDir"]).apply {
        setOnAction {
            val dir: File? = DirectoryChooser().apply {
                title = Strings["setup.general.workingDir"]
                val current = File(workingDirField.text)
                if (current.isDirectory) initialDirectory = current
            }.showDialog(dialogPane.scene?.window)
            if (dir != null) workingDirField.text = dir.absolutePath
        }
    }
    private val closeBehaviorCombo = ComboBox<String>().apply {
        items.addAll("PROMPT", "AUTO_CLOSE", "KEEP_OPEN")
        value = initial.closeBehavior
    }
    private val ttlAssocCheck = CheckBox(Strings["setup.general.ttlAssoc"]).apply {
        isSelected = initial.associateTtl
    }
    private val autoUpdateCheck = CheckBox(Strings["setup.general.autoUpdate"]).apply {
        isSelected = initial.autoUpdateCheck
    }

    init {
        title = Strings["setup.general.title"]
        headerText = Strings["setup.general.header"]
        val grid = GridPane().apply {
            hgap = 10.0; vgap = 8.0; padding = Insets(20.0)
            var r = 0
            add(Label(Strings["setup.general.language"]), 0, r)
            add(HBox(8.0, esRadio, enRadio), 1, r); r++
            add(Label(Strings["setup.general.workingDir"]), 0, r)
            add(HBox(6.0, workingDirField, browseDirButton), 1, r); r++
            add(Label(Strings["setup.general.closeBehavior"]), 0, r); add(closeBehaviorCombo, 1, r); r++
            add(ttlAssocCheck, 1, r); r++
            add(autoUpdateCheck, 1, r); r++
        }
        dialogPane.content = grid
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        setResultConverter { btn ->
            if (btn != ButtonType.OK) null else GeneralResult(
                locale = if (enRadio.isSelected) "en" else "es",
                general = GeneralSettings(
                    workingDirectory = workingDirField.text.ifBlank { System.getProperty("user.home") },
                    closeBehavior = closeBehaviorCombo.value,
                    associateTtl = ttlAssocCheck.isSelected,
                    autoUpdateCheck = autoUpdateCheck.isSelected,
                ),
            )
        }
    }
}