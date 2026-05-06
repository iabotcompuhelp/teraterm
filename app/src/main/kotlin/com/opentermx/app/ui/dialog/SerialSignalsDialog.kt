package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.serial.SerialConnection
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.Window
import javafx.util.Duration

/**
 * Live indicator for the six standard serial control lines plus toggles for the two we own as the
 * DTE side (DTR and RTS). The remaining four are read-only.
 */
class SerialSignalsDialog(owner: Window, private val connection: SerialConnection) : Stage() {

    private val ledDtr = led()
    private val ledRts = led()
    private val ledCts = led()
    private val ledDsr = led()
    private val ledDcd = led()
    private val ledRi = led()

    private val dtrToggle = CheckBox(Strings["serial.signals.assert"])
    private val rtsToggle = CheckBox(Strings["serial.signals.assert"])

    private var pendingDtr: Boolean? = null
    private var pendingRts: Boolean? = null

    private val poller = Timeline(KeyFrame(Duration.millis(400.0), { refresh() }))

    init {
        title = Strings["serial.signals.title"]
        initOwner(owner)
        initModality(Modality.NONE)

        dtrToggle.setOnAction { pendingDtr = dtrToggle.isSelected }
        rtsToggle.setOnAction { pendingRts = rtsToggle.isSelected }

        val grid = GridPane().apply {
            hgap = 16.0; vgap = 8.0; padding = Insets(16.0)
            var r = 0
            add(Label("DTR"), 0, r); add(ledDtr, 1, r); add(dtrToggle, 2, r); r++
            add(Label("RTS"), 0, r); add(ledRts, 1, r); add(rtsToggle, 2, r); r++
            add(Label("CTS"), 0, r); add(ledCts, 1, r); r++
            add(Label("DSR"), 0, r); add(ledDsr, 1, r); r++
            add(Label("DCD"), 0, r); add(ledDcd, 1, r); r++
            add(Label("RI"), 0, r); add(ledRi, 1, r); r++
        }
        val hint = Label(Strings["serial.signals.hint"]).apply {
            isWrapText = true; maxWidth = 360.0
        }
        val layout = VBox(8.0, grid, hint).apply {
            padding = Insets(0.0, 16.0, 16.0, 16.0)
            minWidth = 360.0
        }
        scene = Scene(layout)

        poller.cycleCount = Timeline.INDEFINITE
        poller.play()
        setOnCloseRequest { poller.stop() }
    }

    private fun refresh() {
        // Apply user-driven toggle requests before sampling the line state.
        pendingDtr?.let {
            runCatching { connection.setDTR(it) }
            pendingDtr = null
        }
        pendingRts?.let {
            runCatching { connection.setRTS(it) }
            pendingRts = null
        }
        val s = runCatching { connection.readSignals() }.getOrNull() ?: return
        setLed(ledDtr, s.dtr())
        setLed(ledRts, s.rts())
        setLed(ledCts, s.cts())
        setLed(ledDsr, s.dsr())
        setLed(ledDcd, s.dcd())
        setLed(ledRi, s.ri())
        // Reflect actual hardware state when we don't have a pending change.
        if (pendingDtr == null) dtrToggle.isSelected = s.dtr()
        if (pendingRts == null) rtsToggle.isSelected = s.rts()
    }

    private fun led(): Region {
        val c = Circle(7.0, Color.web("#444"))
        return HBox(c).apply {
            minWidth = 18.0
            HBox.setHgrow(this, Priority.NEVER)
        }
    }

    private fun setLed(led: Region, on: Boolean) {
        val circle = (led as HBox).children.firstOrNull() as? Circle ?: return
        circle.fill = if (on) Color.web("#4caf50") else Color.web("#444")
    }
}