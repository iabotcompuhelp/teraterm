package com.opentermx.app.settings

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.opentermx.common.crypto.SecretCipher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EdgeAgentSettingsTest {
    private class FakeTokenStore(override val isAvailable: Boolean = true) : AgentTokenStore {
        val values = mutableMapOf<String, String>()
        override fun read(reference: String) = values[reference]
        override fun write(reference: String, token: String): Boolean {
            values[reference] = token
            return true
        }
        override fun delete(reference: String) = values.remove(reference) != null
    }

    @Test
    fun `legacy null enabled falls back to environment`() {
        val config = EdgeAgentSettings().runtimeConfig(
            mapOf(
                "OPENTERMX_CONTROL_PLANE_URL" to "https://server.example:8766",
                "OPENTERMX_AGENT_TOKEN" to "0123456789abcdef",
                "OPENTERMX_AGENT_ID" to "win-env",
            ),
        )
        assertEquals("win-env", config?.agentId)
    }

    @Test
    fun `explicit disable overrides environment`() {
        assertNull(
            EdgeAgentSettings(enabled = false).runtimeConfig(
                mapOf(
                    "OPENTERMX_CONTROL_PLANE_URL" to "https://server.example:8766",
                    "OPENTERMX_AGENT_TOKEN" to "0123456789abcdef",
                ),
            ),
        )
    }

    @Test
    fun `saved token is encrypted and builds runtime config`() {
        val plain = "0123456789abcdef"
        val settings = EdgeAgentSettings(
            enabled = true,
            controlPlaneUrl = "https://server.example:8766",
            agentId = "win-ui",
            displayName = "NOC",
            token = SecretCipher.encrypt(plain),
        )
        val json = jacksonObjectMapper().writeValueAsString(AppSettings(edgeAgent = settings))

        assertFalse(json.contains(plain))
        assertTrue(json.contains("ciphertext"))
        assertEquals("win-ui", settings.runtimeConfig(emptyMap())?.agentId)
    }

    @Test
    fun `enabled configuration rejects missing token`() {
        assertThrows(IllegalArgumentException::class.java) {
            EdgeAgentSettings(enabled = true, controlPlaneUrl = "https://server:8766").runtimeConfig(emptyMap())
        }
    }

    @Test
    fun `legacy encrypted token migrates to native store`() {
        val store = FakeTokenStore()
        val plain = "0123456789abcdef"
        val migrated = EdgeAgentSettings(agentId = "win-ui", token = SecretCipher.encrypt(plain))
            .migrateLegacyToken(store)

        assertEquals("OpenTermX/edge-agent/win-ui", migrated.tokenReference)
        assertNull(migrated.token)
        assertEquals(plain, store.values[migrated.tokenReference])
    }

    @Test
    fun `runtime configuration resolves token reference`() {
        val store = FakeTokenStore()
        val reference = AgentTokenStores.referenceFor("win-ui")
        store.values[reference] = "0123456789abcdef"
        val settings = EdgeAgentSettings(
            enabled = true,
            controlPlaneUrl = "https://server.example:8766",
            agentId = "win-ui",
            tokenReference = reference,
        )

        assertEquals("0123456789abcdef", settings.runtimeConfig(emptyMap(), store)?.token)
    }

    @Test
    fun `legacy token remains encrypted when native store is unavailable`() {
        val encrypted = SecretCipher.encrypt("0123456789abcdef")
        val settings = EdgeAgentSettings(agentId = "win-ui", token = encrypted)

        assertEquals(settings, settings.migrateLegacyToken(FakeTokenStore(isAvailable = false)))
    }
}
