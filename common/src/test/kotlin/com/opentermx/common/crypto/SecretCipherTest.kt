package com.opentermx.common.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecretCipherTest {

    @Test
    fun roundtripWithDefaultMaster() {
        val original = "sk-ant-api03-abc123-xyz789"
        val encrypted = SecretCipher.encrypt(original)
        assertFalse(encrypted.ciphertext.isEmpty())
        assertFalse(encrypted.iv.isEmpty())
        assertFalse(encrypted.salt.isEmpty())
        val decrypted = SecretCipher.decrypt(encrypted)
        assertEquals(original, decrypted)
    }

    @Test
    fun roundtripWithOverrideMaster() {
        val original = "AIzaSyA-fakekey-1234567890"
        val master = "test-master-secret".toCharArray()
        val encrypted = SecretCipher.encrypt(original, master)
        assertEquals(original, SecretCipher.decrypt(encrypted, master))
    }

    @Test
    fun ivAndSaltAreUniquePerEncryption() {
        val plain = "same-input"
        val a = SecretCipher.encrypt(plain)
        val b = SecretCipher.encrypt(plain)
        assertNotEquals(a.iv, b.iv)
        assertNotEquals(a.salt, b.salt)
        assertNotEquals(a.ciphertext, b.ciphertext)
        assertEquals(plain, SecretCipher.decrypt(a))
        assertEquals(plain, SecretCipher.decrypt(b))
    }

    @Test
    fun emptyInputReturnsEmptyValue() {
        val encrypted = SecretCipher.encrypt("")
        assertTrue(EncryptedValue.isEmpty(encrypted))
        assertEquals("", SecretCipher.decrypt(EncryptedValue.EMPTY))
    }
}
