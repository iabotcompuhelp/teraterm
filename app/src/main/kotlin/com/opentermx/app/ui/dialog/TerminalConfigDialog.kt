package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.TerminalSettings
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.layout.GridPane

class TerminalConfigDialog(initial: TerminalSettings) : Dialog<TerminalSettings>() {

    private val colsSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(20, 500, initial.cols, 1)
        isEditable = true
    }
    private val rowsSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(5, 200, initial.rows, 1)
        isEditable = true
    }
    private val cursorCombo = ComboBox<String>().apply {
        items.addAll("BLOCK", "BAR", "UNDERLINE")
        value = initial.cursorStyle
    }
    private val blinkCheck = CheckBox().apply { isSelected = initial.cursorBlink }
    private val encodingCombo = ComboBox<String>().apply {
        items.addAll("UTF-8", "Shift_JIS", "ISO-8859-1", "ISO-8859-15", "windows-1252")
        value = initial.encoding
        isEditable = true
    }
    private val newlineCombo = ComboBox<String>().apply {
        items.addAll("CR", "LF", "CRLF")
        value = initial.newlineMode
    }
    private val localEchoCheck = CheckBox().apply { isSelected = initial.localEcho }
    private val scrollCombo = ComboBox<String>().apply {
        items.addAll("JUMP", "SMOOTH")
        value = initial.scrollMode
    }

    init {
        title = Strings["setup.terminal.title"]
        headerText = Strings["setup.terminal.header"]
        val grid = GridPane().apply {
            hgap = 10.0; vgap = 8.0; padding = Insets(20.0)
            var r = 0
            add(Label(Strings["setup.terminal.cols"]), 0, r); add(colsSpinner, 1, r); r++
            add(Label(Strings["setup.terminal.rows"]), 0, r); add(rowsSpinner, 1, r); r++
            add(Label(Strings["setup.terminal.cursorStyle"]), 0, r); add(cursorCombo, 1, r); r++
            add(Label(Strings["setup.terminal.cursorBlink"]), 0, r); add(blinkCheck, 1, r); r++
            add(Label(Strings["setup.terminal.encoding"]), 0, r); add(encodingCombo, 1, r); r++
            add(Label(Strings["setup.terminal.newline"]), 0, r); add(newlineCombo, 1, r); r++
            add(Label(Strings["setup.terminal.localEcho"]), 0, r); add(localEchoCheck, 1, r); r++
            add(Label(Strings["setup.terminal.scrollMode"]), 0, r); add(scrollCombo, 1, r); r++
        }
        dialogPane.content = grid
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        setResultConverter { btn ->
            if (btn != ButtonType.OK) null else TerminalSettings(
                cols = colsSpinner.value,
                rows = rowsSpinner.value,
                cursorStyle = cursorCombo.value,
                cursorBlink = blinkCheck.isSelected,
                encoding = encodingCombo.value,
                newlineMode = newlineCombo.value,
                localEcho = localEchoCheck.isSelected,
                scrollMode = scrollCombo.value,
            )
        }
    }
}