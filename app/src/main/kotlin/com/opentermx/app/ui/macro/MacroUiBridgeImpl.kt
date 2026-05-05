package com.opentermx.app.ui.macro

import com.opentermx.macro.MacroUiBridge
import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.TextInputDialog
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class MacroUiBridgeImpl : MacroUiBridge {

    override fun showMessage(message: String) {
        runOnFxAndWait {
            Alert(Alert.AlertType.INFORMATION, message).apply {
                title = "Macro"
                headerText = null
            }.showAndWait()
        }
    }

    override fun prompt(message: String, defaultValue: String): String {
        val result = AtomicReference("")
        runOnFxAndWait {
            val dialog = TextInputDialog(defaultValue).apply {
                title = "Macro"
                headerText = null
                contentText = message
            }
            val r = dialog.showAndWait()
            if (r.isPresent) result.set(r.get()) else result.set(defaultValue)
        }
        return result.get()
    }

    override fun getClipboard(): String =
        if (Platform.isFxApplicationThread()) Clipboard.getSystemClipboard().string ?: ""
        else {
            val ref = AtomicReference("")
            runOnFxAndWait { ref.set(Clipboard.getSystemClipboard().string ?: "") }
            ref.get()
        }

    override fun setClipboard(text: String) {
        runOnFxAndWait {
            Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(text) })
        }
    }

    private inline fun runOnFxAndWait(crossinline block: () -> Unit) {
        if (Platform.isFxApplicationThread()) {
            block()
        } else {
            val latch = CountDownLatch(1)
            Platform.runLater {
                try { block() } finally { latch.countDown() }
            }
            latch.await()
        }
    }
}