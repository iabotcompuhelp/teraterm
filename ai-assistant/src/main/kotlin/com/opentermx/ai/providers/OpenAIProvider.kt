package com.opentermx.ai.providers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.opentermx.common.ai.ChatMessage
import com.opentermx.common.ai.ConnectionResult
import com.opentermx.common.ai.LLMProvider
import com.opentermx.common.ai.LlmRequest
import com.opentermx.common.ai.LlmResponse
import com.opentermx.common.ai.ProviderKind
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Provider para Chat Completions de OpenAI.
 *  POST https://api.openai.com/v1/chat/completions
 *  Header: Authorization: Bearer <key>
 */
class OpenAIProvider(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://api.openai.com",
) : LLMProvider {

    override val kind: ProviderKind = ProviderKind.OPENAI
    private val mapper = ObjectMapper().registerKotlinModule()

    override fun sendPrompt(request: LlmRequest): LlmResponse {
        val client = httpClient(request.timeoutSeconds)
        val messages = mutableListOf<Map<String, String>>()
        if (request.systemPrompt.isNotBlank()) {
            messages += mapOf("role" to "system", "content" to request.systemPrompt)
        }
        request.messages.forEach { m ->
            val role = when (m.role) {
                ChatMessage.Role.SYSTEM -> "system"
                ChatMessage.Role.ASSISTANT -> "assistant"
                ChatMessage.Role.USER -> "user"
            }
            messages += mapOf("role" to role, "content" to m.content)
        }
        val body = mapOf(
            "model" to request.model.ifBlank { model },
            "messages" to messages,
            "temperature" to request.temperature,
            "max_tokens" to request.maxTokens,
        )
        val httpReq = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("content-type", "application/json")
            .post(mapper.writeValueAsString(body).toRequestBody("application/json".toMediaType()))
            .build()

        val start = System.currentTimeMillis()
        client.newCall(httpReq).execute().use { resp ->
            val latency = System.currentTimeMillis() - start
            if (!resp.isSuccessful) {
                throw com.opentermx.common.ai.LlmException(HttpErrorMapper.fromHttp(resp.code), resp.code, resp.body?.string().orEmpty())
            }
            val payload = mapper.readTree(resp.body?.byteStream())
            val text = payload.path("choices").firstOrNull()?.path("message")?.path("content")?.asText().orEmpty()
            val usedModel = payload.path("model").asText(model)
            val inTok = payload.path("usage").path("prompt_tokens").takeIf { it.isNumber }?.asInt()
            val outTok = payload.path("usage").path("completion_tokens").takeIf { it.isNumber }?.asInt()
            return LlmResponse(text, usedModel, latency, inTok, outTok)
        }
    }

    override fun testConnection(timeoutSeconds: Int): ConnectionResult {
        val start = System.currentTimeMillis()
        return runCatching {
            val resp = sendPrompt(
                LlmRequest(
                    model = model,
                    systemPrompt = "You are a connection-test bot. Reply only with OK.",
                    messages = listOf(ChatMessage(ChatMessage.Role.USER, "respond with OK")),
                    temperature = 0.0,
                    maxTokens = 32,
                    timeoutSeconds = timeoutSeconds,
                )
            )
            ConnectionResult(true, kind, resp.model, resp.latencyMillis)
        }.getOrElse { t ->
            val err = (t as? com.opentermx.common.ai.LlmException)?.error ?: HttpErrorMapper.fromException(t)
            ConnectionResult(false, kind, model, System.currentTimeMillis() - start, err)
        }
    }

    private fun httpClient(timeoutSeconds: Int): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()

    companion object {
        const val DEFAULT_MODEL = "gpt-4o-mini"
        val MODELS = listOf("gpt-4o", "gpt-4o-mini")
    }
}
