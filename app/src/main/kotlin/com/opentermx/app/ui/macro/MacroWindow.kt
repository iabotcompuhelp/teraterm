package com.opentermx.app.ui.macro

import com.opentermx.app.viewmodel.TerminalSessionController
import com.opentermx.macro.MacroAiBridge
import javafx.scene.Scene
import javafx.stage.Stage

class MacroWindow(
    private val owner: Stage,
    private val activeSessions: () -> List<TerminalSessionController>,
    private val aiBridgeProvider: () -> MacroAiBridge = { MacroAiBridge.NoOp() },
) {
    private var stage: Stage? = null

    fun show() {
        stage?.let {
            it.show()
            it.toFront()
            return
        }
        val panel = MacroPanel(activeSessions, aiBridgeProvider)
        val scene = Scene(panel, 900.0, 600.0)
        panel.stylesheets()?.let { scene.stylesheets += it }
        val s = Stage().apply {
            title = "Macros — OpenTermX"
            this.scene = scene
            initOwner(owner)
        }
        s.setOnShowing { panel.refreshSessions() }
        s.show()
        stage = s
    }
}