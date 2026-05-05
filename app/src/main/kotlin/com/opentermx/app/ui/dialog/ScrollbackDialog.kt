package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.layout.GridPane

class ScrollbackDialog(currentLimit: Int) : Dialog<Int>() {

    private val limitSpinner = Spinner<Int>().apply {
        // 0 effectively disables scrollback (only visible viewport kept).
        // 1_000_000 is well past anything useful and keeps the spinner bounded.
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(0, 1_000_000, currentLimit, 1_000)
        isEditable = true
    }

    init {
        title = Strings["scrollback.title"]
        headerText = Strings["scrollback.header"]

        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 8.0
            padding = Insets(20.0)
            add(Label(Strings["scrollback.lines"] + ":"), 0, 0); add(limitSpinner, 1, 0)
            add(Label(Strings["scrollback.hint"]).apply { isWrapText = true; maxWidth = 320.0 }, 1, 1)
        }
        dialogPane.content = grid
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        setResultConverter { btn ->
            if (btn == ButtonType.OK) limitSpinner.value ?: currentLimit else null
        }
    }
}