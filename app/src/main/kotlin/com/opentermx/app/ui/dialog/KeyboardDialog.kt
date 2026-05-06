package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.KeyboardSettings
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.layout.GridPane

/**
 * Setup → Keyboard… — VT-style keyboard behaviour. Independent from menu accelerators
 * (those live in Setup → Shortcuts… via {@link KeybindingsDialog}).
 */
class KeyboardDialog(initial: KeyboardSettings) : Dialog<KeyboardSettings>() {

    private val backspaceCombo = ComboBox<String>().apply {
        items.addAll(BACKSPACE_BS, BACKSPACE_DEL)
        value = if (initial.backspaceSendsDel) BACKSPACE_DEL else BACKSPACE_BS
    }
    private val deleteCombo = ComboBox<String>().apply {
        items.addAll(DELETE_CSI, DELETE_BS)
        value = if (initial.deleteSendsBs) DELETE_BS else DELETE_CSI
    }
    private val metaCheck = CheckBox().apply { isSelected = initial.metaSendsEscape }

    init {
        title = Strings["setup.keyboard.title"]
        headerText = Strings["setup.keyboard.header"]
        val grid = GridPane().apply {
            hgap = 10.0; vgap = 8.0; padding = Insets(20.0)
            var r = 0
            add(Label(Strings["setup.keyboard.backspace"]), 0, r); add(backspaceCombo, 1, r); r++
            add(Label(Strings["setup.keyboard.delete"]), 0, r); add(deleteCombo, 1, r); r++
            add(Label(Strings["setup.keyboard.metaEsc"]), 0, r); add(metaCheck, 1, r); r++
        }
        dialogPane.content = grid
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        setResultConverter { btn ->
            if (btn != ButtonType.OK) null else KeyboardSettings(
                backspaceSendsDel = backspaceCombo.value == BACKSPACE_DEL,
                deleteSendsBs = deleteCombo.value == DELETE_BS,
                metaSendsEscape = metaCheck.isSelected,
            )
        }
    }

    private companion object {
        val BACKSPACE_BS: String get() = Strings["setup.keyboard.backspaceBs"]
        val BACKSPACE_DEL: String get() = Strings["setup.keyboard.backspaceDel"]
        val DELETE_CSI: String get() = Strings["setup.keyboard.deleteCsi"]
        val DELETE_BS: String get() = Strings["setup.keyboard.deleteBs"]
    }
}