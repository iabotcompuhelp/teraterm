package com.opentermx.app.ui.ai

import com.opentermx.ai.ProviderRegistry
import com.opentermx.ai.context.Vendor
import com.opentermx.ai.context.VendorDetector
import com.opentermx.app.settings.AiAssistantSettings
import com.opentermx.common.ai.ChatMessage
import com.opentermx.common.ai.LlmRequest
import com.opentermx.common.ai.LlmResponse
import com.opentermx.common.ai.ProviderKind
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.crypto.SecretCipher
import com.opentermx.common.session.SessionId

/**
 * Lógica compartida para invocar al LLM configurado desde múltiples puntos de entrada
 * (chat panel, bridges de macros). Centraliza:
 *   - Desencriptado de la API key
 *   - Construcción de [LlmRequest] con system prompt + sustituciones (device_context,
 *     vendor, hostname, rag_context)
 *   - Detección de vendor sobre el buffer de la sesión activa
 *   - Consulta a la Knowledge Base (RAG) cuando hay documentos indexados
 *
 * No depende de JavaFX — los bridges UI y headless lo reusan.
 */
object AiInvoker {

    data class Result(
        val response: LlmResponse,
        val vendor: Vendor,
        val ragHits: Int,
    )

    fun invoke(settings: AiAssistantSettings, userPrompt: String, sessionId: String?): Result {
        val kind = settings.providerKind()
        val apiKey = if (kind.isCloud) decryptKey(settings, kind) else ""
        val endpoint = if (kind.isCloud) "" else settings.localEndpointFor(kind)
        val model = settings.modelFor(kind).orEmpty().ifBlank { ProviderRegistry.defaultModelFor(kind) }
        val provider = ProviderRegistry.create(kind, apiKey, model, endpoint)

        val sid = sessionId?.let { SessionId(it) }
        val meta = sid?.let { SessionRegistry.metadataOf(it) }
        val lines = sid?.let { SessionRegistry.lastLinesOf(it, 50) }.orEmpty()
        val deviceContextBlock = if (meta != null) buildString {
            append("Protocolo: ").append(meta.protocol)
            if (!meta.host.isNullOrBlank()) append("\nHost: ").append(meta.host)
            if (meta.port != null) append("\nPuerto: ").append(meta.port)
            if (!meta.username.isNullOrBlank()) append("\nUsuario: ").append(meta.username)
            if (lines.isNotEmpty()) {
                append("\n\nÚltimas líneas del terminal:\n")
                lines.takeLast(50).forEach { append("  ").append(it).append('\n') }
            }
        } else ""

        val vendor = if (settings.detectVendor && lines.isNotEmpty()) {
            VendorDetector.detect(lines.joinToString("\n"))
        } else Vendor.UNKNOWN

        val ragResults = if (settings.knowledgeBaseFiles.isNotEmpty() && userPrompt.isNotBlank()) {
            runCatching { KnowledgeBaseHolder.get(settings).search(userPrompt, settings.ragTopK) }
                .getOrDefault(emptyList())
        } else emptyList()
        val ragContext = if (ragResults.isEmpty()) "" else buildString {
            ragResults.forEachIndexed { idx, r ->
                if (idx > 0) append("\n---\n")
                append("[").append(java.nio.file.Path.of(r.chunk.source).fileName).append("#")
                    .append(r.chunk.chunkIndex).append("]  ").append(r.chunk.text)
            }
        }

        val systemPrompt = settings.systemPrompt
            .replace("{device_context}", deviceContextBlock)
            .replace("{vendor}", vendor.displayName)
            .replace("{hostname}", meta?.host.orEmpty())
            .replace("{rag_context}", ragContext)

        val request = LlmRequest(
            model = model,
            systemPrompt = systemPrompt,
            messages = listOf(ChatMessage(ChatMessage.Role.USER, userPrompt)),
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            timeoutSeconds = 90,
        )
        val response = provider.sendPrompt(request)
        return Result(response, vendor, ragResults.size)
    }

    private fun decryptKey(s: AiAssistantSettings, kind: ProviderKind): String {
        val v = s.apiKeyFor(kind) ?: return ""
        return runCatching { SecretCipher.decrypt(v) }.getOrDefault("")
    }
}
