package com.opentermx.ai.providers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.opentermx.common.ai.ChatMessage
import com.opentermx.common.ai.ConnectionResult
import com.opentermx.common.ai.LLMProvider
import com.opentermx.common.ai.LlmError
import com.opentermx.common.ai.LlmRequest
import com.opentermx.common.ai.LlmResponse
import com.opentermx.common.ai.ProviderKind
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Provider local LM Studio. Expone una API OpenAI-compatible:
 *   POST  {base}/v1/chat/completions     (mismo shape que OpenAI)
 *   GET   {base}/v1/models               → {data:[{id, …}]}
 *
 * No requiere API key. Por defecto escucha en `http://localhost:1234`.
 */
class LMStudioProvider(
    private val baseUrl: String,
    private val model: String = "",
) : LLMProvider {

    override val kind: ProviderKind = ProviderKind.LM_STUDIO
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
            .url("${baseUrl.trimEnd('/')}/v1/chat/completions")
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
            if (model.isBlank()) {
                val models = discoverModels(timeoutSeconds)
                if (models.isEmpty()) {
                    return ConnectionResult(false, kind, "", System.currentTimeMillis() - start, LlmError.NoModels)
                }
                return ConnectionResult(true, kind, models.first(), System.currentTimeMillis() - start)
            }
            val resp = sendPrompt(
                LlmRequest(
                    model = model,
                    systemPrompt = "Reply only with OK.",
                    messages = listOf(ChatMessage(ChatMessage.Role.USER, "respond with OK")),
                    temperature = 0.0,
                    maxTokens = 16,
                    timeoutSeconds = timeoutSeconds,
                )
            )
            ConnectionResult(true, kind, resp.model, resp.latencyMillis)
        }.getOrElse { t ->
            val err = (t as? com.opentermx.common.ai.LlmException)?.error ?: classifyLocalError(t)
            ConnectionResult(false, kind, model, System.currentTimeMillis() - start, err)
        }
    }

    override fun discoverModels(timeoutSeconds: Int): List<String> {
        val client = httpClient(timeoutSeconds)
        val req = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/models")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val payload = mapper.readTree(resp.body?.byteStream())
            return payload.path("data").mapNotNull { it.path("id").asText().ifBlank { null } }
        }
    }

    private fun classifyLocalError(t: Throwable): LlmError {
        val msg = t.message.orEmpty().lowercase()
        return when {
            "out of memory" in msg || "oom" in msg -> LlmError.OutOfMemory
            else -> HttpErrorMapper.fromException(t)
        }
    }

    private fun httpClient(timeoutSeconds: Int): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()

    companion object {
        const val DEFAULT_ENDPOINT = "http://localhost:1234"
    }
}
