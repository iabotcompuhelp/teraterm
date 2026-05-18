package com.opentermx.app.settings

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-WithHmacSHA256 para el PIN que protege el toggle "Modo terminal".
 *
 * No es crypto-grade — es un gate de UI contra cambios accidentales o
 * un usuario casual. PBKDF2 con 120k iteraciones y salt aleatorio de 16 bytes
 * son suficientes para que el JSON persistido no exponga el PIN en claro.
 */
object PinHasher {
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    data class Hashed(val hashBase64: String, val saltBase64: String)

    fun hash(pin: CharArray): Hashed {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val derived = derive(pin, salt)
        return Hashed(
            hashBase64 = Base64.getEncoder().encodeToString(derived),
            saltBase64 = Base64.getEncoder().encodeToString(salt),
        )
    }

    fun verify(pin: CharArray, hashBase64: String, saltBase64: String): Boolean {
        val salt = runCatching { Base64.getDecoder().decode(saltBase64) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(hashBase64) }.getOrNull() ?: return false
        val actual = derive(pin, salt)
        return constantTimeEquals(expected, actual)
    }

    private fun derive(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, ITERATIONS, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
