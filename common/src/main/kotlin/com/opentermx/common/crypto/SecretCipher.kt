package com.opentermx.common.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifrado AES-256-GCM con master key derivada por PBKDF2 a partir de un secreto
 * estable (por defecto: usuario + nombre de máquina). Es transparente: no requiere
 * que el usuario introduzca un PIN. Suficiente contra acceso casual al fichero
 * `~/.opentermx/settings.json`; NO sustituye un keystore del SO contra adversarios
 * con acceso completo a la máquina.
 *
 * Formato persistido (Jackson-serializable): [EncryptedValue] con campos Base64.
 */
data class EncryptedValue(
    val ciphertext: String,
    val iv: String,
    val salt: String,
) {
    companion object {
        @JvmField
        val EMPTY = EncryptedValue("", "", "")

        @JvmStatic
        fun isEmpty(v: EncryptedValue?): Boolean = v == null || v.ciphertext.isEmpty()
    }
}

object SecretCipher {

    private const val PBKDF2_ALGO = "PBKDF2WithHmacSHA256"
    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16
    private const val IV_LENGTH_BYTES = 12 // GCM recommended
    private const val GCM_TAG_BITS = 128
    private const val AES = "AES"
    private const val AES_GCM = "AES/GCM/NoPadding"

    private val secureRandom = SecureRandom()

    private val masterSecret: CharArray by lazy { defaultMasterSecret() }

    private fun defaultMasterSecret(): CharArray {
        val user = System.getProperty("user.name").orEmpty()
        val host = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("local")
        val home = System.getProperty("user.home").orEmpty()
        return "opentermx::$user::$host::$home".toCharArray()
    }

    @JvmStatic
    @JvmOverloads
    fun encrypt(plaintext: String, masterOverride: CharArray? = null): EncryptedValue {
        if (plaintext.isEmpty()) return EncryptedValue.EMPTY
        val salt = ByteArray(SALT_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val key = deriveKey(masterOverride ?: masterSecret, salt)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return EncryptedValue(b64.encodeToString(ct), b64.encodeToString(iv), b64.encodeToString(salt))
    }

    @JvmStatic
    @JvmOverloads
    fun decrypt(value: EncryptedValue, masterOverride: CharArray? = null): String {
        if (EncryptedValue.isEmpty(value)) return ""
        val b64 = Base64.getDecoder()
        val ct = b64.decode(value.ciphertext)
        val iv = b64.decode(value.iv)
        val salt = b64.decode(value.salt)
        val key = deriveKey(masterOverride ?: masterSecret, salt)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun deriveKey(master: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(master, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGO)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, AES)
    }
}
