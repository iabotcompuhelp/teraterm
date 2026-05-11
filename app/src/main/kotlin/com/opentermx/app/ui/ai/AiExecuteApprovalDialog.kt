package com.opentermx.app.ui.ai

import com.opentermx.ai.context.Vendor
import com.opentermx.ai.parse.CodeBlock
import com.opentermx.app.i18n.Strings
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Window
import java.util.concurrent.CompletableFuture

/**
 * Diálogo modal que envuelve uno o más [CommandReviewWidget] para que un macro pueda
 * pedir aprobación humana antes de inyectar comandos generados por la IA.
 *
 * Devuelve el [ReviewOutcome] resuelto por el operador. Si el operador cierra la ventana
 * sin elegir, se considera [ReviewOutcome.Rejected].
 *
 * Está pensado para llamarse desde un thread NO-FX (el thread del macro). Internamente
 * salta al FX thread con [Platform.runLater] y bloquea con un [CompletableFuture] hasta
 * que el operador decide.
 */
object AiExecuteApprovalDialog {

    /**
     * Muestra el diálogo y bloquea hasta que el operador decide. Llamar desde el thread
     * del macro (no FX thread). Si el bloque no contiene comandos, retorna [ReviewOutcome.Rejected]
     * inmediatamente.
     */
    fun showAndWait(owner: Window?, blocks: List<CodeBlock>, vendor: Vendor, prompt: String): ReviewOutcome {
        check(!Platform.isFxApplicationThread()) {
            "AiExecuteApprovalDialog.showAndWait debe llamarse desde un thread no-FX"
        }
        if (blocks.isEmpty() || blocks.all { it.lines.all { l -> l.isBlank() } }) {
            return ReviewOutcome.Rejected
        }
        val future = CompletableFuture<ReviewOutcome>()
        Platform.runLater { openDialog(owner, blocks, vendor, prompt, future) }
        return runCatching { future.get() }.getOrDefault(ReviewOutcome.Rejected)
    }

    private fun openDialog(
        owner: Window?,
        blocks: List<CodeBlock>,
        vendor: Vendor,
        prompt: String,
        future: CompletableFuture<ReviewOutcome>,
    ) {
        val dialog = Dialog<ReviewOutcome>().apply {
            title = Strings["ai.macro.approval.title"]
            headerText = Strings["ai.macro.approval.header"]
            initOwner(owner)
        }
        val body = VBox(10.0).apply { padding = Insets(8.0) }
        body.children += Label(Strings.format("ai.macro.approval.promptLabel", prompt.take(160))).apply {
            isWrapText = true; style = "-fx-text-fill: derive(-fx-text-base-color, 25%);"
        }

        val decisionHolder = arrayOfNulls<ReviewOutcome>(1)
        var pending = blocks.size

        blocks.forEach { block ->
            val widget = CommandReviewWidget(block, vendor) { outcome ->
                // Cuando cualquier widget toma una decisión, cerramos el diálogo. En caso
                // de múltiples bloques, sólo se aplica la primera decisión — la spec no
                // distingue entre bloques y mezclar dos outcomes complicaría el contrato.
                if (decisionHolder[0] == null) {
                    decisionHolder[0] = outcome
                    dialog.result = outcome
                    dialog.close()
                }
                pending--
            }
            body.children += widget
            VBox.setVgrow(widget, Priority.SOMETIMES)
        }

        dialog.dialogPane.content = body
        dialog.dialogPane.buttonTypes.setAll(ButtonType.CANCEL)
        dialog.setResultConverter { btn ->
            decisionHolder[0] ?: ReviewOutcome.Rejected
        }
        val result = dialog.showAndWait()
        future.complete(result.orElse(ReviewOutcome.Rejected))
    }
}
