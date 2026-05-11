package com.opentermx.common.ai

/**
 * Contrato común para proveedores de LLM (cloud y locales).
 * Las implementaciones concretas viven en el módulo `ai-assistant`.
 * Vive en `common` para que `macro-engine` (ai_ask/ai_execute) pueda invocarlo
 * sin depender del módulo ai-assistant.
 */
interface LLMProvider {

    val kind: ProviderKind

    /**
     * Envía un prompt completo y devuelve la respuesta. Llamada bloqueante;
     * el caller decide en qué hilo ejecutarla (típicamente Dispatchers.IO).
     */
    fun sendPrompt(request: LlmRequest): LlmResponse

    /**
     * Verifica conectividad y credenciales con un prompt mínimo ("respond with OK").
     * Captura excepciones y las traduce a [LlmError] localizable.
     */
    fun testConnection(timeoutSeconds: Int = 30): ConnectionResult

    /**
     * Lista los modelos disponibles en el endpoint. Solo aplica a providers locales
     * (Ollama `/api/tags`, LM Studio `/v1/models`). Los cloud retornan lista vacía
     * porque sus modelos están catalogados estáticamente en [ProviderRegistry].
     */
    fun discoverModels(timeoutSeconds: Int = 10): List<String> = emptyList()
}

enum class ProviderKind {
    CLAUDE,
    OPENAI,
    GEMINI,
    OLLAMA,
    LM_STUDIO;

    val isCloud: Boolean get() = this == CLAUDE || this == OPENAI || this == GEMINI
    val isLocal: Boolean get() = !isCloud
}
