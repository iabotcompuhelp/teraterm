package com.opentermx.mcp.handlers

import com.opentermx.ai.context.Vendor
import com.opentermx.mcp.backups.BackupCaptureSource
import com.opentermx.mcp.backups.BackupService
import com.opentermx.mcp.backups.CapturedConfiguration
import java.nio.file.Path
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BackupHandlersTest {
    @TempDir lateinit var root: Path

    private fun service(): BackupService = BackupService(
        root,
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(),
    )

    @Test
    fun `captura por fuente y nunca devuelve contenido ni secreto`() = runBlocking {
        val service = service()
        val source = BackupCaptureSource { alias, sessionId ->
            assertEquals("core-1", alias)
            assertEquals(null, sessionId)
            CapturedConfiguration(
                Vendor.CISCO_IOS,
                "hostname core-1\nenable secret 5 no-exponer",
                "adapter:CLI_SSH",
            )
        }
        val result = BackupDeviceConfigHandler(service, source).invoke(mapOf("deviceAlias" to "core-1"))

        assertTrue(result["backupId"].toString().startsWith("backup-"))
        assertFalse(result.toString().contains("no-exponer"))
        assertFalse(result.containsKey("content"))
        assertEquals(1, service.list("core-1").size)
    }

    @Test
    fun `listado devuelve metadatos sin rutas internas`() = runBlocking {
        val service = service()
        service.store("edge-1", Vendor.ARUBA_OS, "hostname edge-1", "adapter:CLI_SSH")
        val result = ListDeviceBackupsHandler(service).invoke(mapOf("deviceAlias" to "edge-1"))
        @Suppress("UNCHECKED_CAST")
        val item = (result["backups"] as List<Map<String, Any?>>).single()
        assertEquals("edge-1", item["deviceAlias"])
        assertFalse(item.containsKey("encryptedFile"))
        assertFalse(item.containsKey("redactedFile"))
    }

    @Test
    fun `verificacion devuelve integridad sin contenido`() = runBlocking {
        val service = service()
        val backup = service.store("core-2", Vendor.CISCO_IOS, "hostname core-2", "adapter:CLI_SSH")
        val result = VerifyDeviceBackupHandler(service).invoke(
            mapOf("deviceAlias" to "core-2", "backupId" to backup.id),
        )
        assertEquals(true, result["valid"])
        assertEquals(true, result["encryptedContentValid"])
        assertEquals(true, result["redactedContentValid"])
        assertFalse(result.containsKey("content"))
    }

    @Test
    fun `verificacion reporta backup inexistente`() {
        val error = org.junit.jupiter.api.assertThrows<McpToolException> {
            runBlocking {
                VerifyDeviceBackupHandler(service()).invoke(
                    mapOf("deviceAlias" to "core-2", "backupId" to "backup-missing"),
                )
            }
        }
        assertEquals(McpToolException.ErrorCode.NOT_FOUND, error.code)
    }

    @Test
    fun `comparacion devuelve solo hashes y conteos`() = runBlocking {
        val service = service()
        val base = service.store("core-3", Vendor.CISCO_IOS, "hostname core-3", "adapter:CLI_SSH")
        val target = service.store("core-3", Vendor.CISCO_IOS, "hostname core-3\nntp server 1.1.1.1", "adapter:CLI_SSH")
        val result = CompareDeviceBackupHandler(service).invoke(
            mapOf("deviceAlias" to "core-3", "baseBackupId" to base.id, "targetBackupId" to target.id),
        )
        assertEquals(false, result["identical"])
        assertEquals(1, result["addedLines"])
        assertFalse(result.containsKey("content"))
    }
}
