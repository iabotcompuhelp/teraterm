package com.opentermx.app.settings

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regresión Phase 3 Fase 2 — Device Registry. Validamos:
 *  - los 4 campos opcionales nuevos (alias, tags, groups, deviceType) sobreviven roundtrip JSON.
 *  - JSON viejo SIN esos campos deserializa con valores default.
 *  - filterForInventory respeta la regla "solo entries con alias definido".
 *  - findByAlias resuelve por igualdad exacta.
 *  - aliasCollisions detecta duplicados.
 *  - NUNCA aparece `secret` ni `keyPath` en un `InventoryDevice` (test estructural).
 */
class SavedConnectionInventoryTest {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `roundtrip JSON preserva los 4 campos opcionales`() {
        val original = SavedConnection(
            id = "id-1", protocol = "SSH", host = "10.0.0.1", port = 22, username = "admin",
            alias = "core-router-1",
            tags = listOf("core", "lab"),
            groups = listOf("ospf-area-0"),
            deviceType = "cisco_ios",
        )
        val json = mapper.writeValueAsString(original)
        val restored = mapper.readValue(json, SavedConnection::class.java)
        assertEquals("core-router-1", restored.alias)
        assertEquals(listOf("core", "lab"), restored.tags)
        assertEquals(listOf("ospf-area-0"), restored.groups)
        assertEquals("cisco_ios", restored.deviceType)
    }

    @Test
    fun `JSON sin los campos nuevos deserializa con defaults`() {
        val legacy = """{
            "id": "id-old", "protocol": "SSH", "host": "10.0.0.2", "port": 22,
            "username": "admin", "authKind": "NONE", "lastUsedAtMillis": 0
        }""".trimIndent()
        val restored = mapper.readValue(legacy, SavedConnection::class.java)
        assertNull(restored.alias)
        assertEquals(emptyList<String>(), restored.tags)
        assertEquals(emptyList<String>(), restored.groups)
        assertNull(restored.deviceType)
    }

    @Test
    fun `filterForInventory excluye entries sin alias`() {
        val entries = listOf(
            sample("a", alias = "edge-1"),
            sample("b", alias = null),
            sample("c", alias = "core-1"),
        )
        val result = SavedConnections.filterForInventory(entries)
        assertEquals(setOf("edge-1", "core-1"), result.mapNotNull { it.alias }.toSet())
    }

    @Test
    fun `filterForInventory aplica tagsAny como OR dentro y AND con groupsAny`() {
        val entries = listOf(
            sample("a", alias = "alpha", tags = listOf("core", "lab")),
            sample("b", alias = "beta", tags = listOf("edge")),
            sample("c", alias = "gamma", tags = listOf("core"), groups = listOf("ospf")),
        )
        val coreOrEdge = SavedConnections.filterForInventory(entries, tagsAny = listOf("core", "edge"))
        assertEquals(setOf("alpha", "beta", "gamma"), coreOrEdge.mapNotNull { it.alias }.toSet())

        val coreAndOspf = SavedConnections.filterForInventory(
            entries, tagsAny = listOf("core"), groupsAny = listOf("ospf"),
        )
        assertEquals(setOf("gamma"), coreAndOspf.mapNotNull { it.alias }.toSet())
    }

    @Test
    fun `filterForInventory filtra por deviceType case-insensitive`() {
        val entries = listOf(
            sample("a", alias = "x", deviceType = "cisco_ios"),
            sample("b", alias = "y", deviceType = "linux_shell"),
        )
        val matched = SavedConnections.filterForInventory(entries, deviceType = "CISCO_IOS")
        assertEquals(setOf("x"), matched.mapNotNull { it.alias }.toSet())
    }

    @Test
    fun `findByAlias devuelve la entrada exacta y null si no existe`() {
        val entries = listOf(
            sample("a", alias = "core-1"),
            sample("b", alias = "edge-1"),
        )
        assertNotNull(SavedConnections.findByAlias(entries, "core-1"))
        assertNull(SavedConnections.findByAlias(entries, "no-existe"))
        assertNull(SavedConnections.findByAlias(entries, ""))
    }

    @Test
    fun `aliasCollisions reporta los duplicados`() {
        val entries = listOf(
            sample("a", alias = "dup"),
            sample("b", alias = "dup"),
            sample("c", alias = "unique"),
            sample("d", alias = null),
        )
        assertEquals(setOf("dup"), SavedConnections.aliasCollisions(entries))
    }

    @Test
    fun `InventoryDevice no expone secret ni keyPath estructuralmente`() {
        // Verificación a nivel reflection: ningún field del data class lleva nombres
        // sensibles. Si alguien agrega uno, este test rompe y obliga a revisar.
        val sensitiveFieldNames = setOf("secret", "password", "passphrase", "keyPath", "privateKey")
        val fields = com.opentermx.mcp.inventory.InventoryDevice::class.java.declaredFields
            .map { it.name }
        for (sensitive in sensitiveFieldNames) {
            assertFalse(
                sensitive in fields,
                "InventoryDevice expone un field sensible: $sensitive",
            )
        }
        // Verificación positiva: los fields esperados sí están.
        assertTrue("alias" in fields)
        assertTrue("savedConnectionId" in fields)
    }

    private fun sample(
        id: String,
        alias: String? = null,
        tags: List<String> = emptyList(),
        groups: List<String> = emptyList(),
        deviceType: String? = null,
    ) = SavedConnection(
        id = id, protocol = "SSH", host = "10.0.0.1", port = 22, username = "u",
        alias = alias, tags = tags, groups = groups, deviceType = deviceType,
    )
}
