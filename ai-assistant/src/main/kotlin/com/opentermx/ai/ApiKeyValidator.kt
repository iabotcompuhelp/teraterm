package com.opentermx.ai

import com.opentermx.common.ai.ProviderKind

object ApiKeyValidator {

    /**
     * Valida el formato (prefijo/longitud mínima) de una API key antes de hacer
     * una llamada real al provider. No verifica que la key sea válida en el server.
     */
    fun validateFormat(provider: ProviderKind, key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.length < 12) return false
        return when (provider) {
            ProviderKind.CLAUDE -> trimmed.startsWith("sk-ant-")
            ProviderKind.OPENAI -> trimmed.startsWith("sk-") && !trimmed.startsWith("sk-ant-")
            ProviderKind.GEMINI -> trimmed.startsWith("AIza")
            ProviderKind.OLLAMA, ProviderKind.LM_STUDIO -> true // No requieren API key
        }
    }

    fun expectedPrefix(provider: ProviderKind): String = when (provider) {
        ProviderKind.CLAUDE -> "sk-ant-"
        ProviderKind.OPENAI -> "sk-"
        ProviderKind.GEMINI -> "AIza"
        ProviderKind.OLLAMA, ProviderKind.LM_STUDIO -> ""
    }
}
