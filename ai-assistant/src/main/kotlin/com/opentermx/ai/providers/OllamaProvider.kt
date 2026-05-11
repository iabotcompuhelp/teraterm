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
 * Provider local Ollama. Endpoints:
 *   POST  {base}/api/chat    {model, messages, stream:false, options:{temperature, num_predict}}
 *   GET   {base}/api/tags    → {models:[{name, …}]}
 *
 * No requiere API key. Toda la conversación se procesa en localhost.
 */
class OllamaProvider(
    private val baseUrl: String,
    private val model: String = "",
) : LLMProvider {

    override val kind: ProviderKind = ProviderKind.OLLAMA
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
            "stream" to false,
            "options" to mapOf(
                "temperature" to request.temperature,
                "num_predict" to request.maxTokens,
            ),
        )
        val httpReq = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/chat")
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
            val text = payload.path("message").path("content").asText().ifBlank {
                // Variante stream agregada por compatibilidad — concatenar deltas
                payload.path("response").asText("")
            }
            val usedModel = payload.path("model").asText(model)
            val inTok = payload.path("prompt_eval_count").takeIf { it.isNumber }?.asInt()
            val outTok = payload.path("eval_count").takeIf { it.isNumber }?.asInt()
            return LlmResponse(text, usedModel, latency, inTok, outTok)
        }
    }

    override fun testConnection(timeoutSeconds: Int): ConnectionResult {
        val start = System.currentTimeMillis()
        return runCatching {
            // Si no hay modelo configurado, basta con que `/api/tags` responda.
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
            .url("${baseUrl.trimEnd('/')}/api/tags")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val payload = mapper.readTree(resp.body?.byteStream())
            return payload.path("models").mapNotNull { it.path("name").asText().ifBlank { null } }
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
        const val DEFAULT_ENDPOINT = "http://localhost:11434"
    }
}
