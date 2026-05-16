package com.opentermx.app.settings

import com.opentermx.common.credentials.CredentialEntry
import com.opentermx.common.credentials.CredentialStore
import com.opentermx.common.credentials.InMemoryCredentialStore
import com.opentermx.common.connection.SshAuth

/**
 * Implementación productiva de [CredentialStore] respaldada por [AiAssistantSettings.credentials].
 * La lista se relee en cada operación a través de un provider para que los cambios desde la UI
 * se reflejen sin tener que reinstanciar el servidor MCP.
 */
class SettingsCredentialStore(
    private val settingsProvider: () -> AiAssistantSettings,
) : CredentialStore {

    override fun findById(id: String): CredentialEntry? =
        settingsProvider().credentials.firstOrNull { it.id == id }

    override fun resolveSshAuth(id: String): SshAuth? {
        val snapshot = InMemoryCredentialStore(settingsProvider().credentials)
        return snapshot.resolveSshAuth(id)
    }
}