package com.opentermx.mcp.backups

import com.opentermx.ai.context.Vendor
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import javax.crypto.KeyGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BackupServiceTest {
    @TempDir lateinit var root: Path

    private fun service(): BackupService {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return BackupService(root, key, now = { Instant.parse("2026-08-24T12:00:00Z") })
    }

    @Test
    fun `guarda copia integra cifrada e indice redactado`() {
        val clear = "hostname core-1\nenable secret 5 supersecreto\nsnmp-server community privada ro"
        val backup = service().store("core-1", Vendor.CISCO_IOS, clear, "adapter:CLI_SSH")
        val dir = root.resolve("core-1")
        val encrypted = Files.readAllBytes(dir.resolve(backup.encryptedFile))
        val redacted = Files.readString(dir.resolve(backup.redactedFile))

        assertNotEquals(clear, encrypted.toString(Charsets.UTF_8))
        assertFalse(redacted.contains("supersecreto"))
        assertFalse(redacted.contains("privada"))
        assertTrue(redacted.contains("********"))
        assertTrue(service().list("core-1").single().fullContentSha256.isNotBlank())
    }

    @Test
    fun `verifica integridad de ambas copias y detecta manipulacion`() {
        val service = service()
        val backup = service.store("edge-1", Vendor.CISCO_IOS, "hostname edge-1", "adapter:CLI_SSH")
        assertTrue(service.verify("edge-1", backup.id)!!.valid)

        Files.writeString(root.resolve("edge-1").resolve(backup.redactedFile), "alterado")
        val result = service.verify("edge-1", backup.id)!!
        assertTrue(result.encryptedContentValid)
        assertFalse(result.redactedContentValid)
        assertFalse(result.valid)
    }

    @Test
    fun `lista por dispositivo sin mezclar backups`() {
        val service = service()
        service.store("a", Vendor.CISCO_IOS, "hostname a", "adapter:CLI_SSH")
        service.store("b", Vendor.ARUBA_OS, "hostname b", "adapter:CLI_SSH")
        assertEquals(1, service.list("a").size)
        assertEquals("a", service.list("a").single().deviceAlias)
        assertEquals(1, service.list("b").size)
    }

    @Test
    fun `compara copias redactadas sin exponer contenido`() {
        val service = service()
        val base = service.store("core", Vendor.CISCO_IOS, "hostname core\ninterface 1/1/1", "adapter:CLI_SSH")
        val target = service.store(
            "core", Vendor.CISCO_IOS, "hostname core\ninterface 1/1/1\ndescription uplink", "adapter:CLI_SSH",
        )
        val result = service.compare("core", base.id, target.id)!!
        assertFalse(result.identical)
        assertEquals(1, result.addedLines)
        assertEquals(0, result.removedLines)
    }
}
