package com.opentermx.ai

import com.opentermx.common.ai.ProviderKind
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApiKeyValidatorTest {

    @Test
    fun claudeKeysRequireSkAntPrefix() {
        assertTrue(ApiKeyValidator.validateFormat(ProviderKind.CLAUDE, "sk-ant-api03-abc123-xyz789"))
        assertFalse(ApiKeyValidator.validateFormat(ProviderKind.CLAUDE, "sk-abc123def456"))
        assertFalse(ApiKeyValidator.validateFormat(ProviderKind.CLAUDE, "AIzaSyB-bad"))
        assertFalse(ApiKeyValidator.validateFormat(ProviderKind.CLAUDE, ""))
    }

    @Test
    fun openAiKeysRequireSkPrefixButNotSkAnt() {
        assertTrue(ApiKeyValidator.validateFormat(ProviderKind.OPENAI, "sk-abcdef1234567890"))
        assertFalse(ApiKeyValidator.validateFormat(ProviderKind.OPENAI, "sk-ant-api03-anth"))
        assertFalse(ApiKeyValidator.validateFormat(ProviderKind.OPENAI, "AIzaSyB"))
    }

    @Test
    fun geminiKeysRequireAIzaPrefix() {
        assertTrue(ApiKeyValidator.validateFormat(ProviderKind.GEMINI, "AIzaSyB-some-real-looking-key"))
        assertFalse(ApiKeyValidator.validateFormat(ProviderKind.GEMINI, "sk-abc123"))
    }

    @Test
    fun localProvidersAcceptAnyKey() {
        assertTrue(ApiKeyValidator.validateFormat(ProviderKind.OLLAMA, "anything-or-empty"))
        assertTrue(ApiKeyValidator.validateFormat(ProviderKind.LM_STUDIO, "no-key-needed"))
    }
}
