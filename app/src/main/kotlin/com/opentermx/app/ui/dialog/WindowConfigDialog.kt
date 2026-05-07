package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.WindowSettings
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ColorPicker
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.Slider
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.scene.paint.Color

class WindowConfigDialog(
    initial: WindowSettings,
    themeFg: Color,
    themeBg: Color,
) : Dialog<WindowSettings>() {

    private val titleField = TextField(initial.titlePrefix)
    private val transparencySlider = Slider(0.3, 1.0, initial.transparency).apply {
        majorTickUnit = 0.1; isShowTickLabels = true; isShowTickMarks = true
    }
    private val hideTitleBarCheck = CheckBox().apply { isSelected = initial.hideTitleBar }
    private val mouseCursorCombo = ComboBox<String>().apply {
        items.addAll("DEFAULT", "TEXT", "NONE")
        value = initial.mouseCursorMode
    }
    private val fgPicker = ColorPicker(parseHexOr(initial.terminalForeground, themeFg))
    private val bgPicker = ColorPicker(parseHexOr(initial.terminalBackground, themeBg))
    private val fgUseTheme = CheckBox().apply {
        isSelected = initial.terminalForeground.isBlank()
        selectedProperty().addListener { _, _, sel -> fgPicker.isDisable = sel }
        fgPicker.isDisable = isSelected
    }
    private val bgUseTheme = CheckBox().apply {
        isSelected = initial.terminalBackground.isBlank()
        selectedProperty().addListener { _, _, sel -> bgPicker.isDisable = sel }
        bgPicker.isDisable = isSelected
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
            add(Label(Strings["setup.window.fg"]), 0, r); add(fgPicker, 1, r)
            add(themeBox(Strings["setup.window.useTheme"], fgUseTheme), 2, r); r++
            add(Label(Strings["setup.window.bg"]), 0, r); add(bgPicker, 1, r)
            add(themeBox(Strings["setup.window.useTheme"], bgUseTheme), 2, r); r++
        }
        dialogPane.content = grid
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        setResultConverter { btn ->
            if (btn != ButtonType.OK) null else WindowSettings(
                titlePrefix = titleField.text.ifBlank { "COMPUHELP" },
                transparency = transparencySlider.value,
                hideTitleBar = hideTitleBarCheck.isSelected,
                mouseCursorMode = mouseCursorCombo.value,
                terminalForeground = if (fgUseTheme.isSelected) "" else colorToHex(fgPicker.value),
                terminalBackground = if (bgUseTheme.isSelected) "" else colorToHex(bgPicker.value),
            )
        }
    }

    private fun themeBox(label: String, check: CheckBox): javafx.scene.layout.HBox =
        javafx.scene.layout.HBox(4.0, check, Label(label))
}

private fun parseHexOr(hex: String, fallback: Color): Color =
    if (hex.isBlank()) fallback else runCatching { Color.web(hex) }.getOrDefault(fallback)

private fun colorToHex(c: Color): String =
    String.format(
        "#%02x%02x%02x",
        (c.red * 255).toInt().coerceIn(0, 255),
        (c.green * 255).toInt().coerceIn(0, 255),
        (c.blue * 255).toInt().coerceIn(0, 255),
    )