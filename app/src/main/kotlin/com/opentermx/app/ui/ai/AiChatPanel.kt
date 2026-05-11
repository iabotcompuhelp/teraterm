package com.opentermx.app.ui.ai

import com.opentermx.ai.ProviderRegistry
import com.opentermx.ai.audit.AiAuditEntry
import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.ai.context.Vendor
import com.opentermx.ai.context.VendorDetector
import com.opentermx.ai.parse.CodeBlock
import com.opentermx.ai.parse.CodeBlockParser
import com.opentermx.ai.safety.RiskLevel
import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.AiAssistantSettings
import com.opentermx.common.ai.ChatMessage
import com.opentermx.common.ai.CommandSink
import com.opentermx.common.ai.LlmError
import com.opentermx.common.ai.LlmRequest
import com.opentermx.common.ai.ProviderKind
import com.opentermx.common.crypto.EncryptedValue
import com.opentermx.common.crypto.SecretCipher
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextArea
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import kotlin.concurrent.thread

/**
 * Información mínima sobre la sesión activa que el panel inyecta como contexto en cada prompt.
 * Calculada por MainWindow al momento de enviar (vía SessionRegistry).
 */
data class TerminalContextSnapshot(
    val sessionId: String,
    val protocol: String,
    val host: String?,
    val port: Int?,
    val username: String?,
    val terminalLines: List<String>,
) {
    fun deviceContextBlock(): String {
        val sb = StringBuilder()
        sb.append("Protocolo: ").append(protocol)
        if (!host.isNullOrBlank()) sb.append("\nHost: ").append(host)
        if (port != null) sb.append("\nPuerto: ").append(port)
        if (!username.isNullOrBlank()) sb.append("\nUsuario: ").append(username)
        if (terminalLines.isNotEmpty()) {
            sb.append("\n\nÚltimas líneas del terminal:\n")
            terminalLines.takeLast(50).forEach { sb.append("  ").append(it).append('\n') }
        }
        return sb.toString()
    }
}

/**
 * Panel lateral de chat con IA (spec v4 § Panel de Chat IA, línea 229).
 *
 * Se monta a la derecha del tabPane en MainWindow vía un SplitPane.
 * Toggle con Ctrl+I; click en el badge "🟢 IA: …" de la barra de estado abre el diálogo de config.
 */
class AiChatPanel(
    private val getSettings: () -> AiAssistantSettings,
    private val onSettingsUpdated: (AiAssistantSettings) -> Unit,
    private val getTerminalContext: () -> TerminalContextSnapshot?,
    private val getCommandSink: () -> CommandSink?,
    private val onClose: () -> Unit,
    private val auditLog: AiAuditLog = AiAuditLog(),
) : VBox() {

    private var lastUserPrompt: String = ""
    private var lastVendor: Vendor = Vendor.UNKNOWN

    private val providerLabel = Label().apply {
        styleClass += "ai-chat-provider"
        style = "-fx-font-weight: bold;"
    }
    private val closeBtn = Button("×").apply {
        tooltip = Tooltip(Strings["ai.chat.close"])
        style = "-fx-font-size: 14px; -fx-padding: 0 8 0 8;"
        setOnAction { onClose() }
    }
    private val clearBtn = Button(Strings["ai.chat.clear"]).apply {
        setOnAction { clearConversation() }
    }
    private val openSetupBtn = Button(Strings["ai.chat.openSetup"]).apply {
        setOnAction { openSetup() }
    }

    private val conversationBox = VBox(8.0).apply { padding = Insets(12.0) }
    private val scroll = ScrollPane(conversationBox).apply {
        isFitToWidth = true
        VBox.setVgrow(this, Priority.ALWAYS)
        styleClass += "ai-chat-scroll"
    }

    private val inputArea = TextArea().apply {
        promptText = Strings["ai.chat.placeholder"]
        prefRowCount = 3
        isWrapText = true
        addEventFilter(KeyEvent.KEY_PRESSED) { ev ->
            if (ev.code == KeyCode.ENTER && !ev.isShiftDown) {
                ev.consume()
                onSendClicked()
            }
        }
    }
    private val sendBtn = Button(Strings["ai.chat.send"]).apply { setOnAction { onSendClicked() } }

    private val history: MutableList<ChatMessage> = mutableListOf()
    private var pendingBubble: ConversationBubble? = null

    /**
     * Callback opcional para que MainWindow abra el diálogo de configuración cuando
     * el usuario pulsa "Configurar". Se setea desde MainWindow vía property.
     */
    var openSetupCallback: () -> Unit = {}

    init {
        styleClass += "ai-chat-panel"
        spacing = 0.0
        prefWidth = 360.0

        val header = HBox(8.0).apply {
            padding = Insets(8.0, 12.0, 8.0, 12.0)
            alignment = Pos.CENTER_LEFT
            styleClass += "ai-chat-header"
            val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
            children += providerLabel
            children += spacer
            children += clearBtn
            children += closeBtn
        }

        val inputRow = HBox(6.0, inputArea, sendBtn).apply {
            padding = Insets(8.0, 12.0, 12.0, 12.0)
            alignment = Pos.BOTTOM_LEFT
            HBox.setHgrow(inputArea, Priority.ALWAYS)
            styleClass += "ai-chat-input-row"
        }

        children.addAll(header, scroll, inputRow)
        renderEmptyState()
        refreshProviderLabel()
    }

    fun refreshProviderLabel() {
        val s = getSettings()
        val kind = s.providerKind()
        val isLocal = kind.isLocal
        val model = s.lastVerifiedModel ?: s.modelFor(kind).orEmpty()
        val lockIcon = if (isLocal) "🔒 " else ""
        providerLabel.text = if (s.isConfigured()) {
            Strings.format("ai.chat.providerLabel", lockIcon, kind.name, model.ifBlank { "—" })
        } else {
            Strings["ai.chat.notConfigured"]
        }
    }

    private fun openSetup() {
        openSetupCallback()
    }

    fun clearConversation() {
        history.clear()
        conversationBox.children.clear()
        renderEmptyState()
    }

    private fun renderEmptyState() {
        if (conversationBox.children.isNotEmpty()) return
        val s = getSettings()
        val msg = if (s.isConfigured()) {
            Strings["ai.chat.empty"]
        } else {
            Strings["ai.chat.emptyNotConfigured"]
        }
        val placeholder = VBox(12.0).apply {
            alignment = Pos.CENTER
            padding = Insets(32.0)
            children += Label(msg).apply { isWrapText = true; style = "-fx-text-fill: derive(-fx-text-base-color, 30%);" }
            if (!s.isConfigured()) {
                children += openSetupBtn
            }
        }
        conversationBox.children += placeholder
    }

    private fun onSendClicked() {
        val text = inputArea.text.trim()
        if (text.isEmpty()) return
        val settings = getSettings()
        if (!settings.isConfigured()) {
            openSetup()
            return
        }
        // Clear empty placeholder if present
        if (conversationBox.children.size == 1 && conversationBox.children[0] is VBox &&
            (conversationBox.children[0] as VBox).styleClass.contains("ai-chat-panel").not() &&
            conversationBox.children[0].userData == null) {
            conversationBox.children.clear()
        }
        inputArea.clear()

        val userMsg = ChatMessage(ChatMessage.Role.USER, text)
        lastUserPrompt = text
        history += userMsg
        appendBubble(BubbleRole.USER, text)

        val thinking = appendBubble(BubbleRole.THINKING, Strings["ai.chat.thinking"])
        pendingBubble = thinking
        sendBtn.isDisable = true

        val context = if (settings.includeTerminalContext) getTerminalContext() else null
        thread(start = true, isDaemon = true, name = "ai-chat-send") {
            val resultText = runCatching { performRequest(settings, context, history.toList()) }
                .getOrElse { t -> renderErrorMessage(t) }

            Platform.runLater {
                pendingBubble?.let { conversationBox.children.remove(it.node) }
                pendingBubble = null
                history += ChatMessage(ChatMessage.Role.ASSISTANT, resultText.text)
                if (resultText.ragHits > 0) {
                    appendRagBadge(resultText.ragHits)
                }
                appendBubble(if (resultText.isError) BubbleRole.ERROR else BubbleRole.ASSISTANT, resultText.text, resultText.subtitle)
                sendBtn.isDisable = false
                scrollToBottom()
                if (!resultText.isError) {
                    markSuccessfulCall(settings, resultText.modelUsed.orEmpty())
                    appendReviewWidgetsFor(resultText.text)
                }
            }
        }
    }

    private fun appendRagBadge(hits: Int) {
        val badge = Label(Strings.format("ai.chat.ragBadge", hits)).apply {
            style = "-fx-background-color: derive(-fx-base, 12%); -fx-text-fill: derive(-fx-text-base-color, 20%);" +
                " -fx-background-radius: 8; -fx-padding: 2 8 2 8; -fx-font-size: 10.5px;"
        }
        val row = HBox(badge).apply { alignment = Pos.CENTER_LEFT }
        conversationBox.children += row
    }

    /**
     * Si la respuesta de la IA contiene bloques de código, los parsea y monta un
     * [CommandReviewWidget] por bloque debajo de la burbuja del asistente. La decisión
     * del operador (Execute / Reject) se canaliza por `CommandSink` y se registra en
     * el audit log CSV.
     */
    private fun appendReviewWidgetsFor(responseText: String) {
        val blocks = CodeBlockParser.parse(responseText)
        if (blocks.isEmpty()) return
        val ctx = getTerminalContext()
        val vendor = if (getSettings().detectVendor && ctx != null) {
            VendorDetector.detect(ctx.terminalLines.joinToString("\n"))
        } else Vendor.UNKNOWN
        lastVendor = vendor
        blocks.forEach { block -> appendReviewWidget(block, vendor) }
    }

    private fun appendReviewWidget(block: CodeBlock, vendor: Vendor) {
        val widget = CommandReviewWidget(block, vendor) { outcome ->
            when (outcome) {
                is ReviewOutcome.Rejected -> onReviewRejected(block)
                is ReviewOutcome.Execute -> onReviewExecute(outcome.commands, outcome.risks)
            }
        }
        val wrapper = HBox(widget).apply {
            alignment = Pos.CENTER_LEFT
            HBox.setHgrow(widget, Priority.ALWAYS)
        }
        conversationBox.children += wrapper
        Platform.runLater { scrollToBottom() }
    }

    private fun onReviewRejected(block: CodeBlock) {
        appendBubble(
            BubbleRole.ASSISTANT,
            Strings.format("ai.review.rejectedSummary", block.lines.size),
            subtitle = null,
        )
        writeAudit(commands = block.lines, risks = emptyList(), executed = 0, failed = 0, rejected = true, output = "")
    }

    private fun onReviewExecute(commands: List<String>, risks: List<RiskLevel>) {
        val sink = getCommandSink()
        if (sink == null) {
            appendBubble(BubbleRole.ERROR, Strings["ai.review.noSession"], null)
            writeAudit(commands, risks, executed = 0, failed = commands.size, rejected = false, output = "")
            return
        }
        appendBubble(BubbleRole.ASSISTANT, Strings.format("ai.review.executingSummary", commands.size), null)
        thread(start = true, isDaemon = true, name = "ai-exec") {
            var executed = 0
            var failed = 0
            for (cmd in commands) {
                val ok = runCatching { sink.sendLine(cmd) }.getOrDefault(false)
                if (ok) executed++ else failed++
                // pequeña pausa entre líneas para no inundar el buffer del dispositivo
                Thread.sleep(120)
            }
            // Tras enviar, esperamos un breve momento para capturar el output más reciente
            Thread.sleep(800)
            val tail = getTerminalContext()?.terminalLines?.takeLast(30)?.joinToString("\n").orEmpty()
            Platform.runLater {
                appendBubble(
                    if (failed == 0) BubbleRole.ASSISTANT else BubbleRole.ERROR,
                    Strings.format("ai.review.doneSummary", executed, failed),
                    subtitle = if (tail.isNotBlank()) tail.lines().takeLast(4).joinToString(" · ") else null,
                )
                writeAudit(commands, risks, executed = executed, failed = failed, rejected = false, output = tail)
            }
        }
    }

    private fun writeAudit(
        commands: List<String>,
        risks: List<RiskLevel>,
        executed: Int,
        failed: Int,
        rejected: Boolean,
        output: String,
    ) {
        val ctx = getTerminalContext()
        auditLog.append(
            AiAuditEntry(
                timestampMillis = System.currentTimeMillis(),
                sessionId = ctx?.sessionId ?: "no-session",
                host = ctx?.host,
                vendor = lastVendor.takeIf { it != Vendor.UNKNOWN }?.displayName,
                prompt = lastUserPrompt,
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

    private data class RequestResult(
        val text: String,
        val modelUsed: String? = null,
        val subtitle: String? = null,
        val isError: Boolean = false,
        val ragHits: Int = 0,
    )

    private fun performRequest(
        settings: AiAssistantSettings,
        context: TerminalContextSnapshot?,
        messages: List<ChatMessage>,
    ): RequestResult {
        val kind = settings.providerKind()
        val apiKey = if (kind.isCloud) decryptKey(settings, kind) else ""
        val endpoint = if (kind.isCloud) "" else settings.localEndpointFor(kind)
        val model = settings.modelFor(kind).orEmpty().ifBlank { ProviderRegistry.defaultModelFor(kind) }
        val provider = ProviderRegistry.create(kind, apiKey, model, endpoint)

        val deviceContextBlock = context?.deviceContextBlock().orEmpty()
        val vendor = if (settings.detectVendor && context != null) {
            VendorDetector.detect(context.terminalLines.joinToString("\n"))
        } else null

        // Consultar la base de conocimiento (RAG) usando el último mensaje del usuario como query.
        val userQuery = messages.lastOrNull { it.role == ChatMessage.Role.USER }?.content.orEmpty()
        val ragResults = if (settings.knowledgeBaseFiles.isNotEmpty() && userQuery.isNotBlank()) {
            runCatching {
                com.opentermx.app.ui.ai.KnowledgeBaseHolder.get(settings).search(userQuery, settings.ragTopK)
            }.getOrDefault(emptyList())
        } else emptyList()
        val ragContext = if (ragResults.isEmpty()) "" else buildString {
            ragResults.forEachIndexed { idx, r ->
                if (idx > 0) append("\n---\n")
                append("[").append(java.nio.file.Path.of(r.chunk.source).fileName).append("#")
                    .append(r.chunk.chunkIndex).append("]  ")
                append(r.chunk.text)
            }
        }

        val systemPrompt = settings.systemPrompt
            .replace("{device_context}", deviceContextBlock)
            .replace("{vendor}", vendor?.displayName.orEmpty())
            .replace("{hostname}", context?.host.orEmpty())
            .replace("{rag_context}", ragContext)

        val request = LlmRequest(
            model = model,
            systemPrompt = systemPrompt,
            messages = messages,
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            timeoutSeconds = 90,
        )
        val response = provider.sendPrompt(request)
        val subtitle = buildString {
            append(response.model)
            response.latencyMillis.takeIf { it > 0 }?.let { append(" · ").append(it).append(" ms") }
            response.inputTokens?.let { append(" · in=").append(it) }
            response.outputTokens?.let { append(" · out=").append(it) }
            if (vendor != null) append(" · ").append(vendor.displayName)
            if (ragResults.isNotEmpty()) append(" · ").append(Strings.format("ai.chat.ragHits", ragResults.size))
        }
        return RequestResult(response.text, response.model, subtitle, isError = false, ragHits = ragResults.size)
    }

    private fun renderErrorMessage(t: Throwable): RequestResult {
        val err = com.opentermx.common.ai.LlmErrorMapper.fromException(t)
        return RequestResult(formatError(err), isError = true)
    }

    private fun formatError(err: LlmError): String {
        val key = "error.ai.${err.code}"
        val msg = Strings[key].takeIf { it != key } ?: err.message
        return "❌ $msg"
    }

    private fun decryptKey(s: AiAssistantSettings, kind: ProviderKind): String {
        val v: EncryptedValue = s.apiKeyFor(kind) ?: return ""
        return runCatching { SecretCipher.decrypt(v) }.getOrDefault("")
    }

    private fun markSuccessfulCall(settings: AiAssistantSettings, model: String) {
        val updated = settings.copy(
            lastVerifiedAt = System.currentTimeMillis(),
            lastVerifiedProvider = settings.provider,
            lastVerifiedModel = model.ifBlank { settings.lastVerifiedModel },
        )
        onSettingsUpdated(updated)
        refreshProviderLabel()
    }

    private enum class BubbleRole { USER, ASSISTANT, THINKING, ERROR }

    private data class ConversationBubble(val node: javafx.scene.Node)

    private fun appendBubble(role: BubbleRole, text: String, subtitle: String? = null): ConversationBubble {
        val bubble = VBox(4.0).apply {
            maxWidth = 320.0
            padding = Insets(8.0, 12.0, 8.0, 12.0)
            style = when (role) {
                BubbleRole.USER -> "-fx-background-color: -fx-accent; -fx-background-radius: 10; -fx-text-fill: white;"
                BubbleRole.ASSISTANT -> "-fx-background-color: derive(-fx-base, 8%); -fx-background-radius: 10;"
                BubbleRole.THINKING -> "-fx-background-color: derive(-fx-base, 8%); -fx-background-radius: 10; -fx-opacity: 0.7;"
                BubbleRole.ERROR -> "-fx-background-color: derive(red, 70%); -fx-background-radius: 10;"
            }
            val label = Label(text).apply { isWrapText = true; maxWidth = 296.0 }
            if (role == BubbleRole.USER) label.style = "-fx-text-fill: white;"
            children += label
            if (!subtitle.isNullOrBlank()) {
                children += Label(subtitle).apply {
                    style = "-fx-font-size: 10px; -fx-text-fill: derive(-fx-text-base-color, 30%);"
                }
            }
        }
        val wrapper = HBox(bubble).apply {
            alignment = if (role == BubbleRole.USER) Pos.CENTER_RIGHT else Pos.CENTER_LEFT
        }
        conversationBox.children += wrapper
        Platform.runLater { scrollToBottom() }
        return ConversationBubble(wrapper)
    }

    private fun scrollToBottom() {
        scroll.vvalue = 1.0
    }
}
