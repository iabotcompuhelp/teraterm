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
 * Provider para la Messages API de Anthropic.
 *  POST https://api.anthropic.com/v1/messages
 *  Header: x-api-key, anthropic-version: 2023-06-01
 */
class ClaudeProvider(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://api.anthropic.com",
) : LLMProvider {

    override val kind: ProviderKind = ProviderKind.CLAUDE

    private val mapper = ObjectMapper().registerKotlinModule()

    override fun sendPrompt(request: LlmRequest): LlmResponse {
        val client = httpClient(request.timeoutSeconds)
        val body = mapOf(
            "model" to request.model.ifBlank { model },
            "max_tokens" to request.maxTokens,
            "temperature" to request.temperature,
            "system" to request.systemPrompt,
            "messages" to request.messages
                .filter { it.role != ChatMessage.Role.SYSTEM }
                .map { msg ->
                    mapOf(
                        "role" to (if (msg.role == ChatMessage.Role.ASSISTANT) "assistant" else "user"),
                        "content" to msg.content,
                    )
                },
        )
        val json = mapper.writeValueAsString(body)
        val httpReq = Request.Builder()
            .url("$baseUrl/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        val start = System.currentTimeMillis()
        client.newCall(httpReq).execute().use { resp ->
            val latency = System.currentTimeMillis() - start
            if (!resp.isSuccessful) {
                throw com.opentermx.common.ai.LlmException(HttpErrorMapper.fromHttp(resp.code), resp.code, resp.body?.string().orEmpty())
            }
            val payload = mapper.readTree(resp.body?.byteStream())
            val text = payload.path("content").firstOrNull { it["type"]?.asText() == "text" }?.path("text")?.asText()
                ?: payload.path("content").firstOrNull()?.path("text")?.asText().orEmpty()
            val usedModel = payload.path("model").asText(model)
            val inTok = payload.path("usage").path("input_tokens").takeIf { it.isNumber }?.asInt()
            val outTok = payload.path("usage").path("output_tokens").takeIf { it.isNumber }?.asInt()
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
        const val DEFAULT_MODEL = "claude-sonnet-4-20250514"
        val MODELS = listOf(
            "claude-sonnet-4-20250514",
            "claude-opus-4-20250115",
        )
    }
}

