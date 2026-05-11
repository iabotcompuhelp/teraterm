package com.opentermx.app.ui.ai

import com.opentermx.ai.audit.AiAuditEntry
import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.ai.parse.CodeBlockParser
import com.opentermx.ai.safety.RiskLevel
import com.opentermx.app.settings.AiAssistantSettings
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.session.SessionId
import com.opentermx.macro.AiExecuteResult
import com.opentermx.macro.MacroAiBridge
import javafx.stage.Window

/**
 * Bridge IA con UI para macros interactivos (MacroWindow + auto-login).
 *
 * - `ask`: invoca al LLM configurado y devuelve texto plano.
 * - `execute`: invoca al LLM, parsea bloques de código, abre un diálogo modal con
 *   [AiExecuteApprovalDialog] sobre el [stage] de la ventana principal, y al aprobar
 *   inyecta línea por línea por el [com.opentermx.common.ai.CommandSink] de la sesión.
 *   Cada llamada termina con una entrada en [AiAuditLog].
 */
class JavaFxMacroAiBridge(
    private val owner: () -> Window?,
    private val settingsProvider: () -> AiAssistantSettings,
    private val auditLog: AiAuditLog = AiAuditLog(),
) : MacroAiBridge {

    override fun ask(prompt: String, sessionId: String?): String {
        val settings = settingsProvider()
        require(settings.isConfigured()) { "AI not configured" }
        return AiInvoker.invoke(settings, prompt, sessionId).response.text
    }

    override fun execute(prompt: String, sessionId: String?): AiExecuteResult {
        val settings = settingsProvider()
        require(settings.isConfigured()) { "AI not configured" }
        val invocation = AiInvoker.invoke(settings, prompt, sessionId)
        val blocks = CodeBlockParser.parse(invocation.response.text)
        if (blocks.isEmpty()) {
            audit(prompt, sessionId, invocation.vendor.displayName, emptyList(), emptyList(),
                executed = 0, failed = 0, rejected = false, output = invocation.response.text)
            return AiExecuteResult.noCommands(invocation.response.text)
        }
        val outcome = AiExecuteApprovalDialog.showAndWait(owner(), blocks, invocation.vendor, prompt)
        return handleOutcome(outcome, prompt, sessionId, invocation.vendor.displayName)
    }

    private fun handleOutcome(
        outcome: ReviewOutcome,
        prompt: String,
        sessionId: String?,
        vendor: String,
    ): AiExecuteResult = when (outcome) {
        is ReviewOutcome.Rejected -> {
            audit(prompt, sessionId, vendor, emptyList(), emptyList(),
                executed = 0, failed = 0, rejected = true, output = "")
            AiExecuteResult.rejected(emptyList())
        }
        is ReviewOutcome.Execute -> executeCommands(outcome.commands, outcome.risks, prompt, sessionId, vendor)
    }

    private fun executeCommands(
        commands: List<String>,
        risks: List<RiskLevel>,
        prompt: String,
        sessionId: String?,
        vendor: String,
    ): AiExecuteResult {
        if (commands.isEmpty()) return AiExecuteResult.noCommands("")
        val sid = sessionId?.let { SessionId(it) }
        val sink = sid?.let { SessionRegistry.sinkOf(it) }
            ?: return AiExecuteResult.error("Sesión sin sink: $sessionId")
        var executed = 0
        var failed = 0
        for (cmd in commands) {
            val ok = runCatching { sink.sendLine(cmd) }.getOrDefault(false)
            if (ok) executed++ else failed++
            Thread.sleep(120)
        }
        val tail = sid?.let { SessionRegistry.lastLinesOf(it, 20).joinToString("\n") }.orEmpty()
        audit(prompt, sessionId, vendor, commands, risks,
            executed = executed, failed = failed, rejected = false, output = tail)
        val outcomeKind = if (executed == commands.size) AiExecuteResult.Outcome.APPROVED
        else AiExecuteResult.Outcome.PARTIAL
        return AiExecuteResult(outcomeKind, commands, executed, failed, null)
    }

    private fun audit(
        prompt: String,
        sessionId: String?,
        vendor: String?,
        commands: List<String>,
        risks: List<RiskLevel>,
        executed: Int,
        failed: Int,
        rejected: Boolean,
        output: String,
    ) {
        val sid = sessionId ?: "macro"
        val host = sid.takeIf { it.isNotBlank() }?.let { SessionId(it) }
            ?.let { SessionRegistry.metadataOf(it) }?.host
        auditLog.append(
            AiAuditEntry(
                timestampMillis = System.currentTimeMillis(),
                sessionId = sid,
                host = host,
                vendor = vendor?.takeIf { it.isNotBlank() && it != "Unknown" },
                prompt = prompt,
                commands = commands,
                commandRisks = risks,
                executedCount = executed,
                skippedCount = (commands.size - executed - failed).coerceAtLeast(0),
                failedCount = failed,
                rejected = rejected,
                outputTail = output,
            )
        )
    }
}
