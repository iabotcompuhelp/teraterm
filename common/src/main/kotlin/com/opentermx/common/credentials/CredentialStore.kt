package com.opentermx.common.credentials

import com.opentermx.common.connection.SshAuth
import com.opentermx.common.crypto.EncryptedValue
import com.opentermx.common.crypto.SecretCipher

/**
 * Abstracción del keychain — el `mcp-server` y el módulo `app` consultan handles opacos
 * (`credentialRef`) sin tocar el plaintext más que para construir un [SshAuth] al abrir
 * una sesión SSH. Implementaciones:
 *
 * - [EmptyCredentialStore]: tests headless y arranque sin settings (no devuelve nada).
 * - [InMemoryCredentialStore]: tests con entradas pre-cargadas.
 * - `SettingsCredentialStore` en `app/` lee de `AiAssistantSettings.credentials`.
 */
interface CredentialStore {

    fun findById(id: String): CredentialEntry?

    /**
     * Resuelve un handle a un [SshAuth] listo para usar en `SshConnection`. Devuelve `null` si:
     *  - El id no existe.
     *  - La entrada es `SSH_KEY` pero la ruta apunta a un archivo inexistente.
     *  - El descifrado falla (clave de cifrado distinta, datos corruptos, etc).
     */
    fun resolveSshAuth(id: String): SshAuth?

    object Empty : CredentialStore {
        override fun findById(id: String): CredentialEntry? = null
        override fun resolveSshAuth(id: String): SshAuth? = null
    }
}

/** Implementación in-memory — útil para tests. Acepta un `decryptor` inyectable. */
class InMemoryCredentialStore(
    private val entries: List<CredentialEntry>,
    private val decryptor: (EncryptedValue) -> String = SecretCipher::decrypt,
) : CredentialStore {

    override fun findById(id: String): CredentialEntry? = entries.firstOrNull { it.id == id }

    override fun resolveSshAuth(id: String): SshAuth? {
        val entry = findById(id) ?: return null
        return when (entry.kind) {
            CredentialKind.PASSWORD -> {
                val secret = entry.secret?.takeUnless { EncryptedValue.isEmpty(it) } ?: return null
                val plaintext = runCatching { decryptor(secret) }.getOrNull() ?: return null
                SshAuth.Password(plaintext.toCharArray())
            }
            CredentialKind.SSH_KEY -> {
                val keyPath = entry.keyPath?.takeIf { it.isNotBlank() } ?: return null
                val passphrase = entry.passphrase
                    ?.takeUnless { EncryptedValue.isEmpty(it) }
                    ?.let { runCatching { decryptor(it) }.getOrNull() }
                    ?.toCharArray()
                SshAuth.PublicKey(keyPath, passphrase)
            }
        }
    }
}