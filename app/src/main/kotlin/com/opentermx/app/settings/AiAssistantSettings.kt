package com.opentermx.app.settings

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.opentermx.common.ai.ProviderKind
import com.opentermx.common.crypto.EncryptedValue

/**
 * Configuración persistida del asistente IA. Las API keys se almacenan cifradas con AES-256-GCM
 * (`apiKeys` mapea nombre de provider → [EncryptedValue]).
 *
 * Coincide con el diálogo Setup → AI Assistant… definido en la spec v4.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AiAssistantSettings(
    val provider: String = ProviderKind.CLAUDE.name,
    val apiKeys: Map<String, EncryptedValue> = emptyMap(),
    val localEndpoints: Map<String, String> = mapOf(
        ProviderKind.OLLAMA.name to "http://localhost:11434",
        ProviderKind.LM_STUDIO.name to "http://localhost:1234",
    ),
    val selectedModels: Map<String, String> = emptyMap(),
    val temperature: Double = 0.2,
    val maxTokens: Int = 2048,
    val includeTerminalContext: Boolean = true,
    val detectVendor: Boolean = true,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val knowledgeBaseFiles: List<String> = emptyList(),
    val ragChunkSize: Int = 500,
    val ragChunkOverlap: Int = 50,
    val ragTopK: Int = 5,
    /**
     * UI hint: marca si el operador ya verificó la conexión con "Test connection".
     * `null` o `false` ⇒ 🟡 amarillo en la barra de estado.
     */
    val lastVerifiedAt: Long? = null,
    val lastVerifiedProvider: String? = null,
    val lastVerifiedModel: String? = null,
) {
    fun providerKind(): ProviderKind = runCatching { ProviderKind.valueOf(provider) }
        .getOrDefault(ProviderKind.CLAUDE)

    fun apiKeyFor(kind: ProviderKind): EncryptedValue? = apiKeys[kind.name]

    fun modelFor(kind: ProviderKind): String? = selectedModels[kind.name]

    fun localEndpointFor(kind: ProviderKind): String =
        localEndpoints[kind.name] ?: when (kind) {
            ProviderKind.OLLAMA -> "http://localhost:11434"
            ProviderKind.LM_STUDIO -> "http://localhost:1234"
            else -> ""
        }

    @JsonIgnore
    fun isConfigured(): Boolean {
        val k = providerKind()
        return if (k.isCloud) {
            val v = apiKeyFor(k)
            v != null && !EncryptedValue.isEmpty(v)
        } else {
            localEndpointFor(k).isNotBlank()
        }
    }

    @JsonIgnore
    fun isVerified(): Boolean = lastVerifiedAt != null && lastVerifiedProvider == provider

    companion object {
        // Spec v4 línea 430.
        const val DEFAULT_SYSTEM_PROMPT = """Eres un asistente experto en configuración de equipos de red. Tu rol:

1. Recibirás instrucciones en lenguaje natural del operador de red
2. Generarás los comandos CLI exactos para el dispositivo conectado
3. SIEMPRE especifica el vendor y la versión del CLI que estás usando
4. Responde SOLO con los comandos necesarios en un bloque de código
5. Debajo del bloque de código, explica brevemente qué hace cada comando
6. Si la instrucción es ambigua, pide aclaración ANTES de generar comandos
7. NUNCA generes comandos destructivos sin advertir explícitamente al operador
8. Si detectas que un comando puede causar pérdida de conectividad (ej: cambiar IP de management, shutdown de interfaz activa), advierte con "⚠️ ADVERTENCIA: este comando puede causar pérdida de conectividad"
9. Adapta la sintaxis al vendor detectado:
   - Cisco IOS/IOS-XE: configure terminal, interface X, exit, end, write memory
   - Cisco NX-OS: configure terminal, feature X, interface X, copy running-config startup-config
   - Juniper JunOS: configure, set X, commit, show | compare
   - Huawei VRP: system-view, interface X, quit, save
   - MikroTik RouterOS: /interface, /ip address, /system
   - FortiNet FortiOS: config system interface, set X, end
10. Si hay políticas de infraestructura disponibles (RAG), SIEMPRE respétalas. Aplica naming conventions, VLANs asignadas, rangos de IP y configuraciones de seguridad según el documento de políticas.
11. Contexto del dispositivo actual: {device_context}
12. Políticas de infraestructura aplicables: {rag_context}
"""
    }
}
