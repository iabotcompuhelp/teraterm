package com.opentermx.app.settings

import java.security.MessageDigest

/**
 * Entrada de la tabla multi-token del servidor MCP. El token plaintext sólo se ve una
 * vez (al generarlo); lo único persistido es [hash] = SHA-256 hex del plaintext. Esto
 * limita el blast radius si `settings.json` se filtra.
 */
data class McpTokenEntry(
    val id: String,
    val name: String,
    val hash: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long? = null,
    val scope: Scope = Scope.FULL,
    val lastUsedAtMillis: Long? = null,
) {

    enum class Scope { FULL, READ_ONLY }

    /** Devuelve `true` si el token expiró según [now]. Tokens sin expiración nunca expiran. */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expiresAtMillis != null && now > expiresAtMillis

    companion object {

        /** Hash SHA-256 hex del [plaintext]. Es lo único que persistimos del token. */
        fun hashOf(plaintext: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(plaintext.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** Devuelve `true` si [plaintext] matchea algún token vigente en [list]. */
        fun matchAny(plaintext: String, list: List<McpTokenEntry>): McpTokenEntry? {
            if (plaintext.isBlank() || list.isEmpty()) return null
            val h = hashOf(plaintext)
            return list.firstOrNull { it.hash == h && !it.isExpired() }
        }
    }
}