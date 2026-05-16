package com.opentermx.app.ui.ai

import com.opentermx.ai.context.Vendor
import com.opentermx.ai.parse.CodeBlock
import com.opentermx.ai.safety.ClassifiedCommand
import com.opentermx.mcp.security.ApprovalDecision
import com.opentermx.mcp.security.ApprovalGate
import javafx.stage.Window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Adaptador del [ApprovalGate] del módulo `mcp-server` que delega en el
 * [AiExecuteApprovalDialog] (JavaFX). Vive en `app` porque depende de JavaFX, manteniendo
 * `mcp-server` puramente JVM/no-UI.
 *
 * El diálogo ya implementa internamente el patrón `Platform.runLater {} + CompletableFuture`,
 * así que acá solo hace falta cambiar el thread a [Dispatchers.IO] para no bloquear el
 * thread del servidor MCP mientras esperamos al operador (puede tardar minutos).
 */
class JavaFxApprovalGate(
    private val owner: () -> Window?,
) : ApprovalGate {

    override suspend fun reviewCommands(
        prompt: String,
        vendor: Vendor,
        classifications: List<ClassifiedCommand>,
    ): ApprovalDecision = withContext(Dispatchers.IO) {
        val block = CodeBlock(
            lines = classifications.map { it.raw },
            language = "",
            explanation = prompt,
        )
        val outcome = AiExecuteApprovalDialog.showAndWait(owner(), listOf(block), vendor, prompt)
        when (outcome) {
            is ReviewOutcome.Execute -> ApprovalDecision.Approve(outcome.commands, outcome.risks)
            is ReviewOutcome.Rejected -> ApprovalDecision.Reject
        }
    }
}