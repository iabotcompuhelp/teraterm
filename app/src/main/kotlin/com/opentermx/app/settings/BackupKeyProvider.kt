package com.opentermx.app.settings

import com.opentermx.common.crypto.SecretCipher
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Clave estable de backups: Credential Manager en Windows, fallback cifrado compatible. */
object BackupKeyProvider {
    private const val REFERENCE = "OpenTermX/backups/master-key"
    private val fallbackFile: Path = SettingsStore.configDir.resolve("backup-key.json")

    @Synchronized
    fun loadOrCreate(): SecretKey {
        val store = AgentTokenStores.system
        val encoded = if (store.isAvailable) {
            store.read(REFERENCE) ?: generateEncoded().also {
                check(store.write(REFERENCE, it)) { "no se pudo guardar la clave de backups en Credential Manager" }
            }
        } else {
            readFallback() ?: generateEncoded().also(::writeFallback)
        }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size == 32) { "clave de backups inválida" }
        return SecretKeySpec(bytes, "AES")
    }

    private fun generateEncoded(): String = ByteArray(32).also(SecureRandom()::nextBytes)
        .let(Base64.getEncoder()::encodeToString)

    private fun readFallback(): String? = runCatching {
        if (!Files.isRegularFile(fallbackFile)) null
        else SecretCipher.decrypt(
            com.fasterxml.jackson.databind.ObjectMapper().readValue(
                fallbackFile.toFile(), com.opentermx.common.crypto.EncryptedValue::class.java,
            ),
        )
    }.getOrNull()

    private fun writeFallback(encoded: String) {
        Files.createDirectories(fallbackFile.parent)
        com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
            .writeValue(fallbackFile.toFile(), SecretCipher.encrypt(encoded))
    }
}
