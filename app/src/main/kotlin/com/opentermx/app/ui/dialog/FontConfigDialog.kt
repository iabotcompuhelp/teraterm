package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.layout.GridPane
import javafx.scene.text.Font

data class FontChoice(val family: String, val size: Double)

class FontConfigDialog(currentFamily: String, currentSize: Double) : Dialog<FontChoice>() {

    private val familyCombo = ComboBox<String>().apply {
        items.setAll(Font.getFamilies())
        value = if (currentFamily in items) currentFamily else items.firstOrNull { it.equals("Consolas", ignoreCase = true) } ?: items.firstOrNull()
        isEditable = true
        maxWidth = Double.MAX_VALUE
    }
    private val sizeSpinner = Spinner<Double>().apply {
        valueFactory = SpinnerValueFactory.DoubleSpinnerValueFactory(8.0, 36.0, currentSize, 0.5)
        isEditable = true
    }

    init {
        title = Strings["font.title"]
        headerText = Strings["font.title"]

        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 8.0
            padding = Insets(20.0)
            add(Label(Strings["font.family"] + ":"), 0, 0); add(familyCombo, 1, 0)
            add(Label(Strings["font.size"] + ":"), 0, 1); add(sizeSpinner, 1, 1)
        }
        dialogPane.content = grid
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        setResultConverter { btn ->
            if (btn == ButtonType.OK) {
                FontChoice(familyCombo.value ?: "Consolas", sizeSpinner.value ?: 14.0)
            } else null
        }
    }
}