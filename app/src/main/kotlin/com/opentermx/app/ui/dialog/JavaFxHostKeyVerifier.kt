package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.common.connection.HostKeyDecision
import com.opentermx.common.connection.HostKeyPrompt
import com.opentermx.common.connection.HostKeyStatus
import com.opentermx.common.connection.HostKeyVerifier
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.layout.GridPane
import javafx.scene.layout.Priority
import javafx.scene.text.Font
import javafx.stage.Stage
import javafx.stage.Window
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

class JavaFxHostKeyVerifier(private val ownerSupplier: () -> Window?) : HostKeyVerifier {

    private val log = LoggerFactory.getLogger(javaClass)

    constructor(ownerStage: Stage) : this({ ownerStage })

    override fun verify(prompt: HostKeyPrompt): HostKeyDecision {
        if (Platform.isFxApplicationThread()) {
            return runCatching { showDialog(prompt) }
                .onFailure { log.warn("Diálogo de host key falló", it) }
                .getOrDefault(HostKeyDecision.REJECT)
        }
        val future = CompletableFuture<HostKeyDecision>()
        Platform.runLater {
            try {
                future.complete(showDialog(prompt))
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return try {
            future.get()
        } catch (e: Exception) {
            log.warn("Esperando decisión de host key", e)
            HostKeyDecision.REJECT
        }
    }

    private fun showDialog(prompt: HostKeyPrompt): HostKeyDecision {
        val dialog = Dialog<HostKeyDecision>().apply {
            title = Strings["hostkey.title"]
            headerText = when (prompt.status) {
                HostKeyStatus.NEW -> Strings["hostkey.newHeader"]
                HostKeyStatus.CHANGED -> Strings["hostkey.changedHeader"]
            }
            ownerSupplier()?.let { initOwner(it) }
        }

        val acceptSave = ButtonType(Strings["hostkey.acceptAndSave"], ButtonData.OK_DONE)
        val acceptOnce = ButtonType(Strings["hostkey.acceptOnce"], ButtonData.APPLY)
        val reject = ButtonType(Strings["hostkey.reject"], ButtonData.CANCEL_CLOSE)

        // CHANGED: do not offer to persist; user must edit known_hosts manually after
        // a key change to avoid silently overwriting a possibly compromised key.
        if (prompt.status == HostKeyStatus.CHANGED) {
            dialog.dialogPane.buttonTypes.setAll(reject, acceptOnce)
        } else {
            dialog.dialogPane.buttonTypes.setAll(reject, acceptOnce, acceptSave)
        }

        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 8.0
            padding = Insets(20.0)
            add(Label(Strings["hostkey.host"]), 0, 0)
            add(Label("${prompt.host}:${prompt.port}"), 1, 0)
            add(Label(Strings["hostkey.keyType"]), 0, 1)
            add(Label(prompt.keyType), 1, 1)
            add(Label(Strings["hostkey.fingerprint"]), 0, 2)
            add(monoLabel(prompt.fingerprintSha256), 1, 2)
            if (prompt.status == HostKeyStatus.CHANGED && prompt.previousFingerprints.isNotEmpty()) {
                add(Label(Strings["hostkey.previousFingerprint"]), 0, 3)
                add(monoLabel(prompt.previousFingerprints.joinToString("\n")), 1, 3)
                val warning = Label(Strings["hostkey.changedWarning"]).apply {
                    isWrapText = true
                    style = "-fx-text-fill: #c0392b; -fx-font-weight: bold;"
                }
                GridPane.setColumnSpan(warning, 2)
                add(warning, 0, 4)
            }
            columnConstraints.add(javafx.scene.layout.ColumnConstraints().apply { hgrow = Priority.NEVER })
            columnConstraints.add(javafx.scene.layout.ColumnConstraints().apply { hgrow = Priority.ALWAYS })
        }

        dialog.dialogPane.content = grid

        dialog.setResultConverter { btn ->
            when (btn) {
                acceptSave -> HostKeyDecision.ACCEPT_AND_SAVE
                acceptOnce -> HostKeyDecision.ACCEPT_ONCE
                else -> HostKeyDecision.REJECT
            }
        }

        return dialog.showAndWait().orElse(HostKeyDecision.REJECT)
    }

    private fun monoLabel(text: String): Label = Label(text).apply {
        font = Font.font("Monospaced", font.size)
        isWrapText = true
    }
}