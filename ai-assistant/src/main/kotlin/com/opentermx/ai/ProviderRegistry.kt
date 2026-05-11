package com.opentermx.ai

import com.opentermx.ai.providers.ClaudeProvider
import com.opentermx.ai.providers.GeminiProvider
import com.opentermx.ai.providers.LMStudioProvider
import com.opentermx.ai.providers.OllamaProvider
import com.opentermx.ai.providers.OpenAIProvider
import com.opentermx.common.ai.LLMProvider
import com.opentermx.common.ai.ProviderKind

/**
 * Construye un [LLMProvider] concreto según [ProviderKind] y la configuración resuelta
 * (API key descifrada, modelo, endpoint local en Fase 2).
 *
 * Los providers locales (Ollama / LM Studio) aterrizarán en Fase 2.
 */
object ProviderRegistry {

    fun modelsFor(kind: ProviderKind): List<String> = when (kind) {
        ProviderKind.CLAUDE -> ClaudeProvider.MODELS
        ProviderKind.OPENAI -> OpenAIProvider.MODELS
        ProviderKind.GEMINI -> GeminiProvider.MODELS
        ProviderKind.OLLAMA, ProviderKind.LM_STUDIO -> emptyList() // se descubren runtime en Fase 2
    }

    fun defaultModelFor(kind: ProviderKind): String = when (kind) {
        ProviderKind.CLAUDE -> ClaudeProvider.DEFAULT_MODEL
        ProviderKind.OPENAI -> OpenAIProvider.DEFAULT_MODEL
        ProviderKind.GEMINI -> GeminiProvider.DEFAULT_MODEL
        ProviderKind.OLLAMA, ProviderKind.LM_STUDIO -> ""
    }

    fun defaultEndpointFor(kind: ProviderKind): String = when (kind) {
        ProviderKind.OLLAMA -> OllamaProvider.DEFAULT_ENDPOINT
        ProviderKind.LM_STUDIO -> LMStudioProvider.DEFAULT_ENDPOINT
        else -> ""
    }

    fun consoleUrlFor(kind: ProviderKind): String = when (kind) {
        ProviderKind.CLAUDE -> "https://console.anthropic.com/settings/keys"
        ProviderKind.OPENAI -> "https://platform.openai.com/api-keys"
        ProviderKind.GEMINI -> "https://aistudio.google.com/apikey"
        ProviderKind.OLLAMA -> "https://ollama.com/download"
        ProviderKind.LM_STUDIO -> "https://lmstudio.ai"
    }

    /**
     * Crea un provider listo para llamar.
     * Para cloud providers usa [apiKey]; para locales usa [localEndpoint].
     */
    fun create(
        kind: ProviderKind,
        apiKey: String,
        model: String,
        localEndpoint: String = "",
    ): LLMProvider = when (kind) {
        ProviderKind.CLAUDE -> ClaudeProvider(apiKey, model.ifBlank { ClaudeProvider.DEFAULT_MODEL })
        ProviderKind.OPENAI -> OpenAIProvider(apiKey, model.ifBlank { OpenAIProvider.DEFAULT_MODEL })
        ProviderKind.GEMINI -> GeminiProvider(apiKey, model.ifBlank { GeminiProvider.DEFAULT_MODEL })
        ProviderKind.OLLAMA -> OllamaProvider(
            baseUrl = localEndpoint.ifBlank { OllamaProvider.DEFAULT_ENDPOINT },
            model = model,
        )
        ProviderKind.LM_STUDIO -> LMStudioProvider(
            baseUrl = localEndpoint.ifBlank { LMStudioProvider.DEFAULT_ENDPOINT },
            model = model,
        )
    }
}
