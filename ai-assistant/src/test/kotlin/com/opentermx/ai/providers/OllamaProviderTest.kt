package com.opentermx.ai.providers

import com.opentermx.common.ai.ChatMessage
import com.opentermx.common.ai.LlmError
import com.opentermx.common.ai.LlmRequest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OllamaProviderTest {

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
    fun sendPromptParsesResponse() {
        server.enqueue(
            MockResponse()
                .setBody("""{"model":"llama3","message":{"role":"assistant","content":"Hello back"},"prompt_eval_count":12,"eval_count":5}""")
                .setHeader("content-type", "application/json")
        )
        val provider = OllamaProvider(server.url("/").toString(), "llama3")
        val response = provider.sendPrompt(
            LlmRequest(
                model = "llama3",
                systemPrompt = "be brief",
                messages = listOf(ChatMessage(ChatMessage.Role.USER, "hi")),
                temperature = 0.1,
                maxTokens = 64,
                timeoutSeconds = 5,
            )
        )
        assertEquals("Hello back", response.text)
        assertEquals("llama3", response.model)
        assertEquals(12, response.inputTokens)
        assertEquals(5, response.outputTokens)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/chat", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"model\":\"llama3\""))
        assertTrue(body.contains("\"stream\":false"))
        assertTrue(body.contains("\"num_predict\":64"))
    }

    @Test
    fun discoverModelsParsesTags() {
        server.enqueue(
            MockResponse()
                .setBody("""{"models":[{"name":"llama3:70b"},{"name":"qwen2.5:7b"}]}""")
                .setHeader("content-type", "application/json")
        )
        val models = OllamaProvider(server.url("/").toString()).discoverModels(5)
        assertEquals(listOf("llama3:70b", "qwen2.5:7b"), models)
    }

    @Test
    fun testConnectionWithEmptyModelUsesTags() {
        server.enqueue(
            MockResponse()
                .setBody("""{"models":[{"name":"llama3:7b"}]}""")
                .setHeader("content-type", "application/json")
        )
        val result = OllamaProvider(server.url("/").toString()).testConnection(5)
        assertTrue(result.success)
        assertEquals("llama3:7b", result.model)
    }

    @Test
    fun testConnectionReportsNoModelsWhenEmpty() {
        server.enqueue(
            MockResponse()
                .setBody("""{"models":[]}""")
                .setHeader("content-type", "application/json")
        )
        val result = OllamaProvider(server.url("/").toString()).testConnection(5)
        assertFalse(result.success)
        assertNotNull(result.error)
        assertTrue(result.error is LlmError.NoModels)
    }
}
