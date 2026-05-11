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
 * Provider para Google Gemini (v1beta).
 *  POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={apiKey}
 *  Body: { contents: [{role, parts:[{text}]}], systemInstruction: {parts:[{text}]}, generationConfig: { temperature, maxOutputTokens } }
 */
class GeminiProvider(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
) : LLMProvider {

    override val kind: ProviderKind = ProviderKind.GEMINI
    private val mapper = ObjectMapper().registerKotlinModule()

    override fun sendPrompt(request: LlmRequest): LlmResponse {
        val client = httpClient(request.timeoutSeconds)
        val contents = request.messages
            .filter { it.role != ChatMessage.Role.SYSTEM }
            .map { m ->
                val role = if (m.role == ChatMessage.Role.ASSISTANT) "model" else "user"
                mapOf("role" to role, "parts" to listOf(mapOf("text" to m.content)))
            }
        val body = mapOf(
            "contents" to contents,
            "systemInstruction" to mapOf("parts" to listOf(mapOf("text" to request.systemPrompt))),
            "generationConfig" to mapOf(
                "temperature" to request.temperature,
                "maxOutputTokens" to request.maxTokens,
            ),
        )
        val effectiveModel = request.model.ifBlank { model }
        val url = "$baseUrl/v1beta/models/$effectiveModel:generateContent?key=$apiKey"
        val httpReq = Request.Builder()
            .url(url)
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
            val parts = payload.path("candidates").firstOrNull()?.path("content")?.path("parts")
            val text = parts?.mapNotNull { it.path("text").asText().ifBlank { null } }?.joinToString("") ?: ""
            val inTok = payload.path("usageMetadata").path("promptTokenCount").takeIf { it.isNumber }?.asInt()
            val outTok = payload.path("usageMetadata").path("candidatesTokenCount").takeIf { it.isNumber }?.asInt()
            return LlmResponse(text, effectiveModel, latency, inTok, outTok)
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
        const val DEFAULT_MODEL = "gemini-2.0-flash"
        val MODELS = listOf("gemini-2.0-flash", "gemini-2.0-pro")
    }
}
