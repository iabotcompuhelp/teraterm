package com.opentermx.app.ui.ai

import com.opentermx.app.settings.AiAssistantSettings
import com.opentermx.common.ai.ProviderKind
import com.opentermx.mcp.application.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelHandoffServiceTest {
    @Test
    fun `preview conserva journal evidencia y construye prompt de confirmacion`() = runBlocking {
        var captured: Map<String, Any?> = emptyMap()
        val service = ModelHandoffService { _, args ->
            captured = args
            ToolExecutionResult.Success(
                mapOf(
                    "schemaVersion" to "1.0",
                    "operationId" to "op-1",
                    "journal" to listOf(mapOf("sequence" to 1), mapOf("sequence" to 2)),
                    "evidence" to listOf(mapOf("id" to "snap-1", "sha256" to "a".repeat(64))),
                    "factualSummary" to "ok",
                ),
            )
        }
        val settings = AiAssistantSettings(provider = ProviderKind.CLAUDE.name)

        val preview = service.preview(settings, ProviderKind.OLLAMA, "llama3.2", "fallback local")

        assertEquals("CLAUDE", captured["sourceProvider"])
        assertEquals("OLLAMA", captured["targetProvider"])
        assertEquals(2, preview.journalEvents)
        assertEquals(1, preview.evidenceCount)
        assertTrue(preview.contextPrompt.contains("No repitas tools mutativas"))
        assertTrue(preview.contextPrompt.contains("snap-1"))
    }

    @Test
    fun `confirm cambia proveedor modelo y limpia verificacion previa`() {
        val service = ModelHandoffService { _, _ -> error("unused") }
        val settings = AiAssistantSettings(
            provider = ProviderKind.CLAUDE.name,
            lastVerifiedAt = 1,
            lastVerifiedProvider = ProviderKind.CLAUDE.name,
            lastVerifiedModel = "old",
        )
        val preview = ModelHandoffService.Preview(
            "op-1", ProviderKind.OLLAMA, "llama3.2", "{}", "handoff", 1, 0,
        )

        val updated = service.confirm(settings, preview)

        assertEquals(ProviderKind.OLLAMA, updated.providerKind())
        assertEquals("llama3.2", updated.modelFor(ProviderKind.OLLAMA))
        assertNull(updated.lastVerifiedAt)
        assertTrue(updated.isConfigured())
    }
}
