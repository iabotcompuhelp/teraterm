package com.opentermx.common.credentials

import com.opentermx.common.crypto.EncryptedValue

enum class CredentialKind { PASSWORD, SSH_KEY }

/**
 * Entrada del keychain interno de OpenTermX. Los secretos (`secret`, `passphrase`) se persisten
 * cifrados con [com.opentermx.common.crypto.SecretCipher]; el plaintext solo vive en memoria
 * el tiempo justo para construir un [com.opentermx.common.connection.SshAuth] al abrir una
 * sesión. Nunca se devuelve por MCP — el cliente externo solo conoce el `id` opaco.
 *
 * - `PASSWORD`: usa `username` + `secret` (plaintext de la password).
 * - `SSH_KEY`: usa `keyPath` (ruta al privkey en disco) y opcionalmente `passphrase` cifrada.
 *   `username` aplica también para login SSH con clave.
 */
data class CredentialEntry(
    val id: String,
    val name: String,
    val kind: CredentialKind,
    val username: String? = null,
    val secret: EncryptedValue? = null,
    val keyPath: String? = null,
    val passphrase: EncryptedValue? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
)