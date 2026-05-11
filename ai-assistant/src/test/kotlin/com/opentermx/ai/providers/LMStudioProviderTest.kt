package com.opentermx.ai.providers

import com.opentermx.common.ai.ChatMessage
import com.opentermx.common.ai.LlmRequest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LMStudioProviderTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sendPromptParsesOpenAiShape() {
        server.enqueue(
            MockResponse()
                .setBody(
                    """{"model":"llama-3","choices":[{"message":{"role":"assistant","content":"Pong"}}],"usage":{"prompt_tokens":4,"completion_tokens":1}}"""
                )
                .setHeader("content-type", "application/json")
        )
        val provider = LMStudioProvider(server.url("/").toString(), "llama-3")
        val resp = provider.sendPrompt(
            LlmRequest(
                model = "llama-3",
                systemPrompt = "echo",
                messages = listOf(ChatMessage(ChatMessage.Role.USER, "ping")),
                temperature = 0.1,
                maxTokens = 16,
                timeoutSeconds = 5,
            )
        )
        assertEquals("Pong", resp.text)
        assertEquals("llama-3", resp.model)
        assertEquals(4, resp.inputTokens)
        assertEquals(1, resp.outputTokens)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/chat/completions", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"max_tokens\":16"))
    }

    @Test
    fun discoverModelsParsesData() {
        server.enqueue(
            MockResponse()
                .setBody("""{"data":[{"id":"qwen2.5-7b-instruct"},{"id":"llama-3-8b"}],"object":"list"}""")
                .setHeader("content-type", "application/json")
        )
        val models = LMStudioProvider(server.url("/").toString()).discoverModels(5)
        assertEquals(listOf("qwen2.5-7b-instruct", "llama-3-8b"), models)
    }
}
