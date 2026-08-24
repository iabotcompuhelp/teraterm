package com.opentermx.app.inventory

import com.opentermx.app.settings.AppSettings
import com.opentermx.app.settings.SavedAuthKind
import com.opentermx.common.crypto.SecretCipher
import com.opentermx.mcp.telemetry.TelemetryStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class InventoryImportCommitServiceTest {
    @Test
    fun `commit cifra clave y crea inventario sin base disponible`() {
        val row = InventoryImportRow(
            2, "10.0.0.10", "sw-core", "admin", "secreto-123", "Aruba", "JL660A",
            "switch", "aa:bb:cc:dd:ee:ff", "CN001", 22, "SSH",
        )
        val result = InventoryImportCommitService(TelemetryStore { null }).commit(
            InventoryImportPreview(listOf(row), emptyList()), AppSettings(), "operator:admin",
        )
        val saved = result.settings.savedConnections.single()
        assertEquals(1, result.imported)
        assertEquals(0, result.databaseRecorded)
        assertEquals(SavedAuthKind.PASSWORD, saved.authKind)
        assertEquals("sw-core", saved.alias)
        assertEquals("aa:bb:cc:dd:ee:ff", saved.baseMac)
        assertEquals("CN001", saved.serialNumber)
        assertFalse(saved.toString().contains("secreto-123"))
        assertEquals("secreto-123", SecretCipher.decrypt(saved.secret!!))
    }
}
