package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.WindowSettings
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.Slider
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane

class WindowConfigDialog(initial: WindowSettings) : Dialog<WindowSettings>() {

    private val titleField = TextField(initial.titlePrefix)
    private val transparencySlider = Slider(0.3, 1.0, initial.transparency).apply {
        majorTickUnit = 0.1; isShowTickLabels = true; isShowTickMarks = true
    }
    private val hideTitleBarCheck = CheckBox().apply { isSelected = initial.hideTitleBar }
    private val mouseCursorCombo = ComboBox<String>().apply {
        items.addAll("DEFAULT", "TEXT", "NONE")
        value = initial.mouseCursorMode
    }

    init {
        title = Strings["setup.window.title"]
        headerText = Strings["setup.window.header"]
        val grid = GridPane().apply {
            hgap = 10.0; vgap = 8.0; padding = Insets(20.0)
            var r = 0
            add(Label(Strings["setup.window.titlePrefix"]), 0, r); add(titleField, 1, r); r++
            add(Label(Strings["setup.window.transparency"]), 0, r); add(transparencySlider, 1, r); r++
            add(Label(Strings["setup.window.hideTitleBar"]), 0, r); add(hideTitleBarCheck, 1, r); r++
            add(Label(Strings["setup.window.mouseCursor"]), 0, r); add(mouseCursorCombo, 1, r); r++
        }
        dialogPane.content = grid
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        setResultConverter { btn ->
            if (btn != ButtonType.OK) null else WindowSettings(
                titlePrefix = titleField.text.ifBlank { "OpenTermX" },
                transparency = transparencySlider.value,
                hideTitleBar = hideTitleBarCheck.isSelected,
                mouseCursorMode = mouseCursorCombo.value,
            )
        }
    }
}