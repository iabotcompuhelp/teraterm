package com.opentermx.mcp.backups

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.opentermx.ai.context.Vendor
import com.opentermx.ai.safety.CredentialRedactor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class DeviceBackup(
    val id: String,
    val deviceAlias: String,
    val vendor: String,
    val capturedAt: String,
    val source: String,
    val fullContentSha256: String,
    val redactedContentSha256: String,
    val encryptedFile: String,
    val redactedFile: String,
)

data class BackupVerification(
    val backupId: String,
    val encryptedContentValid: Boolean,
    val redactedContentValid: Boolean,
) {
    val valid: Boolean get() = encryptedContentValid && redactedContentValid
}

data class BackupComparison(
    val baseBackupId: String,
    val targetBackupId: String,
    val identical: Boolean,
    val addedLines: Int,
    val removedLines: Int,
    val baseRedactedSha256: String,
    val targetRedactedSha256: String,
)

/**
 * Almacena una copia íntegra cifrada y un artefacto redactado apto para inspección.
 * [configuration] debe provenir de un adaptador del dispositivo, nunca del buffer visual.
 */
class BackupService(
    root: Path,
    private val encryptionKey: SecretKey,
    private val redactor: CredentialRedactor = CredentialRedactor(),
    private val now: () -> Instant = Instant::now,
) {
    private val root = root.toAbsolutePath().normalize()
    private val mapper = ObjectMapper().registerKotlinModule()
    private val random = SecureRandom()

    fun store(
        deviceAlias: String,
        vendor: Vendor,
        configuration: String,
        source: String,
    ): DeviceBackup {
        require(deviceAlias.isNotBlank()) { "deviceAlias no puede estar vacío" }
        require(configuration.isNotEmpty()) { "la configuración capturada está vacía" }
        require(source.isNotBlank()) { "source no puede estar vacío" }

        val id = "backup-" + UUID.randomUUID().toString().take(12)
        val dir = safeDeviceDir(deviceAlias)
        Files.createDirectories(dir)
        val redacted = redactor.redact(configuration, vendor)
        val encryptedName = "$id.enc"
        val redactedName = "$id.redacted.txt"
        val metadataName = "$id.json"
        writeAtomically(dir.resolve(encryptedName), encrypt(configuration.toByteArray(Charsets.UTF_8)))
        writeAtomically(dir.resolve(redactedName), redacted.toByteArray(Charsets.UTF_8))

        val backup = DeviceBackup(
            id = id,
            deviceAlias = deviceAlias,
            vendor = vendor.name,
            capturedAt = now().toString(),
            source = source,
            fullContentSha256 = sha256(configuration.toByteArray(Charsets.UTF_8)),
            redactedContentSha256 = sha256(redacted.toByteArray(Charsets.UTF_8)),
            encryptedFile = encryptedName,
            redactedFile = redactedName,
        )
        writeAtomically(
            dir.resolve(metadataName),
            mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(backup),
        )
        return backup
    }

    fun list(deviceAlias: String): List<DeviceBackup> {
        val dir = safeDeviceDir(deviceAlias)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { files ->
            files.filter { it.fileName.toString().endsWith(".json") }
                .map { runCatching { mapper.readValue<DeviceBackup>(it.toFile()) }.getOrNull() }
                .filter { it != null }
                .map { it!! }
                .sorted(compareByDescending { it.capturedAt })
                .toList()
        }
    }

    fun verify(deviceAlias: String, backupId: String): BackupVerification? {
        val backup = list(deviceAlias).firstOrNull { it.id == backupId } ?: return null
        val dir = safeDeviceDir(deviceAlias)
        val fullValid = runCatching {
            sha256(decrypt(Files.readAllBytes(safeChild(dir, backup.encryptedFile)))) == backup.fullContentSha256
        }.getOrDefault(false)
        val redactedValid = runCatching {
            sha256(Files.readAllBytes(safeChild(dir, backup.redactedFile))) == backup.redactedContentSha256
        }.getOrDefault(false)
        return BackupVerification(backup.id, fullValid, redactedValid)
    }

    fun compare(deviceAlias: String, baseBackupId: String, targetBackupId: String): BackupComparison? {
        val backups = list(deviceAlias).associateBy { it.id }
        val base = backups[baseBackupId] ?: return null
        val target = backups[targetBackupId] ?: return null
        val dir = safeDeviceDir(deviceAlias)
        val baseText = Files.readString(safeChild(dir, base.redactedFile))
        val targetText = Files.readString(safeChild(dir, target.redactedFile))
        require(sha256(baseText.toByteArray(Charsets.UTF_8)) == base.redactedContentSha256) {
            "la copia redactada base no supera la verificación de integridad"
        }
        require(sha256(targetText.toByteArray(Charsets.UTF_8)) == target.redactedContentSha256) {
            "la copia redactada destino no supera la verificación de integridad"
        }
        val baseCounts = baseText.lines().groupingBy { it }.eachCount()
        val targetCounts = targetText.lines().groupingBy { it }.eachCount()
        val lines = baseCounts.keys + targetCounts.keys
        val added = lines.sumOf { (targetCounts[it] ?: 0).minus(baseCounts[it] ?: 0).coerceAtLeast(0) }
        val removed = lines.sumOf { (baseCounts[it] ?: 0).minus(targetCounts[it] ?: 0).coerceAtLeast(0) }
        return BackupComparison(
            baseBackupId, targetBackupId,
            identical = base.redactedContentSha256 == target.redactedContentSha256,
            addedLines = added,
            removedLines = removed,
            baseRedactedSha256 = base.redactedContentSha256,
            targetRedactedSha256 = target.redactedContentSha256,
        )
    }

    private fun safeDeviceDir(deviceAlias: String): Path {
        val safeName = deviceAlias.replace(Regex("[^A-Za-z0-9._-]"), "_")
        require(safeName.isNotBlank() && safeName != "." && safeName != "..") { "deviceAlias inválido" }
        return safeChild(root, safeName)
    }

    private fun safeChild(parent: Path, name: String): Path {
        val result = parent.resolve(name).normalize()
        require(result.startsWith(parent)) { "ruta de backup inválida" }
        return result
    }

    private fun encrypt(clear: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, GCMParameterSpec(TAG_BITS, iv))
        return iv + cipher.doFinal(clear)
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > IV_BYTES) { "backup cifrado truncado" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, GCMParameterSpec(TAG_BITS, payload.copyOfRange(0, IV_BYTES)))
        return cipher.doFinal(payload.copyOfRange(IV_BYTES, payload.size))
    }

    private fun writeAtomically(target: Path, bytes: ByteArray) {
        val temp = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            Files.write(temp, bytes)
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
