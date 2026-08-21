package com.opentermx.app.ui.ai

import com.opentermx.ai.providers.ClaudeProvider
import com.opentermx.ai.providers.OllamaProvider
import com.opentermx.ai.providers.OpenAIProvider
import com.opentermx.app.settings.AiAssistantSettings
import com.opentermx.common.ai.ChatMessage
import com.opentermx.common.ai.LLMProvider
import com.opentermx.common.ai.LlmRequest
import com.opentermx.common.ai.ProviderKind
import com.opentermx.mcp.application.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Pruebas opt-in contra APIs reales; nunca leen secretos de archivos. */
class LiveModelHandoffTest {
    @Test
    fun `OpenAI a Ollama conserva el contexto operacional`() = runBlocking {
        requireLiveTests()
        val targetModel = requiredEnv("OLLAMA_MODEL")
        verifyHandoff(
            OpenAIProvider(requiredEnv("OPENAI_API_KEY"), env("OPENAI_MODEL") ?: OpenAIProvider.DEFAULT_MODEL,
                env("OPENAI_BASE_URL") ?: "https://api.openai.com"), ProviderKind.OPENAI,
            OllamaProvider(env("OLLAMA_BASE_URL") ?: OllamaProvider.DEFAULT_ENDPOINT, targetModel),
            ProviderKind.OLLAMA, targetModel,
        )
    }

    @Test
    fun `Claude a OpenAI conserva el contexto operacional`() = runBlocking {
        requireLiveTests()
        val targetModel = env("OPENAI_MODEL") ?: OpenAIProvider.DEFAULT_MODEL
        verifyHandoff(
            ClaudeProvider(requiredEnv("ANTHROPIC_API_KEY"), env("ANTHROPIC_MODEL") ?: ClaudeProvider.DEFAULT_MODEL,
                env("ANTHROPIC_BASE_URL") ?: "https://api.anthropic.com"), ProviderKind.CLAUDE,
            OpenAIProvider(requiredEnv("OPENAI_API_KEY"), targetModel,
                env("OPENAI_BASE_URL") ?: "https://api.openai.com"), ProviderKind.OPENAI, targetModel,
        )
    }

    private suspend fun verifyHandoff(source: LLMProvider, sourceKind: ProviderKind,
        target: LLMProvider, targetKind: ProviderKind, targetModel: String) {
        val sourceResponse = source.sendPrompt(request("Responde literalmente con SOURCE-ACK y nada más."))
        assertContains(sourceResponse.text, "SOURCE-ACK")
        val service = ModelHandoffService { tool, args ->
            assertTrue(tool == "export_operation_handoff")
            assertTrue(args["sourceProvider"] == sourceKind.name)
            assertTrue(args["targetProvider"] == targetKind.name)
            ToolExecutionResult.Success(handoffPayload(sourceResponse.text))
        }
        val preview = service.preview(AiAssistantSettings(provider = sourceKind.name),
            targetKind, targetModel, "live validation")
        assertTrue(preview.decisionCount == 1)
        assertTrue(preview.evidenceCount == 1)
        assertTrue(preview.unknownMutationCount == 1)
        val response = target.sendPrompt(LlmRequest(
            model = targetModel,
            systemPrompt = preview.contextPrompt,
            messages = listOf(ChatMessage(ChatMessage.Role.USER,
                "Devuelve solo estos marcadores separados por espacios: " +
                    "OBJ-NORTH SCOPE-CORE DEC-ALLOW EVID-9 UNKNOWN-CHECK")),
            temperature = 0.0, maxTokens = 64, timeoutSeconds = 120,
        ))
        listOf("OBJ-NORTH", "SCOPE-CORE", "DEC-ALLOW", "EVID-9", "UNKNOWN-CHECK")
            .forEach { assertContains(response.text, it) }
    }

    private fun request(prompt: String) = LlmRequest("", prompt,
        listOf(ChatMessage(ChatMessage.Role.USER, "Confirma recepción.")), 0.0, 16, 60)

    private fun handoffPayload(sourceAck: String): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to "1.0", "operationId" to "live-handoff-validation", "status" to "UNKNOWN",
        "objective" to "OBJ-NORTH", "context" to mapOf("scope" to mapOf("devices" to listOf("SCOPE-CORE"))),
        "journal" to listOf(
            mapOf("sequence" to 1, "status" to "SUCCEEDED", "payload" to mapOf("ack" to sourceAck)),
            mapOf("sequence" to 2, "status" to "UNKNOWN", "payload" to mapOf("message" to "UNKNOWN-CHECK"))),
        "decisions" to listOf(mapOf("decision" to "APPROVED", "rationale" to "DEC-ALLOW")),
        "evidence" to listOf(mapOf("id" to "EVID-9", "sha256" to "a".repeat(64))),
        "factualSummary" to "Verificar UNKNOWN-CHECK antes de reintentar.",
        "openRisks" to listOf("UNKNOWN-CHECK"), "nextSteps" to listOf("Verificar estado real del dispositivo"),
    )

    private fun requireLiveTests() = assumeTrue(env("OPENTERMX_LIVE_LLM_TESTS") == "true",
        "pruebas live no habilitadas")
    private fun requiredEnv(name: String): String {
        val value = env(name)
        assumeTrue(!value.isNullOrBlank(), "falta $name")
        return value!!
    }
    private fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf(String::isNotEmpty)
    private fun assertContains(actual: String, expected: String) = assertTrue(
        actual.contains(expected, ignoreCase = true), "respuesta sin $expected: $actual")
}
