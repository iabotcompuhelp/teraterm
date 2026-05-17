package com.opentermx.app.settings

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.opentermx.common.crypto.EncryptedValue
import com.opentermx.common.crypto.SecretCipher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SavedConnectionsTest {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    private fun entry(
        id: String = "id-${System.nanoTime()}",
        host: String = "10.0.0.1",
        port: Int = 22,
        username: String = "admin",
        authKind: SavedAuthKind = SavedAuthKind.NONE,
        secret: EncryptedValue? = null,
        keyPath: String? = null,
        lastUsed: Long = System.currentTimeMillis(),
    ) = SavedConnection(
        id = id, protocol = "SSH", host = host, port = port, username = username,
        authKind = authKind, secret = secret, keyPath = keyPath, lastUsedAtMillis = lastUsed,
    )

    @Test
    fun `findMostRecent devuelve la entrada con lastUsedAtMillis máximo`() {
        val older = entry(id = "a", lastUsed = 1_000L)
        val newer = entry(id = "b", lastUsed = 5_000L)
        val list = listOf(older, newer)

        val result = SavedConnections.findMostRecent(list, "SSH", "10.0.0.1", 22)

        assertEquals("b", result?.id)
    }

    @Test
    fun `findMostRecent ignora protocolo y puerto distintos`() {
        val list = listOf(
            entry(id = "ssh22", port = 22),
            entry(id = "ssh2222", port = 2222),
            entry(id = "telnet23", port = 23).copy(protocol = "TELNET"),
        )

        assertEquals("ssh22", SavedConnections.findMostRecent(list, "SSH", "10.0.0.1", 22)?.id)
        assertEquals("ssh2222", SavedConnections.findMostRecent(list, "SSH", "10.0.0.1", 2222)?.id)
        assertNull(SavedConnections.findMostRecent(list, "TELNET", "10.0.0.1", 22))
    }

    @Test
    fun `findMostRecent compara host case-insensitive`() {
        val list = listOf(entry(host = "Router-Core-01"))
        val result = SavedConnections.findMostRecent(list, "SSH", "router-core-01", 22)
        assertNotNull(result)
    }

    @Test
    fun `upsert reemplaza la entrada existente para mismo host port username`() {
        val initial = listOf(entry(id = "old", username = "admin", lastUsed = 1_000L))
        val replacement = entry(id = "new", username = "admin")

        val updated = SavedConnections.upsert(initial, replacement, bumpLastUsed = false)

        assertEquals(1, updated.size)
        assertEquals("new", updated.single().id)
    }

    @Test
    fun `upsert agrega entrada distinta cuando username difiere`() {
        val initial = listOf(entry(id = "admin", username = "admin"))
        val ops = entry(id = "ops", username = "ops")

        val updated = SavedConnections.upsert(initial, ops, bumpLastUsed = false)

        assertEquals(2, updated.size)
        assertTrue(updated.any { it.id == "admin" })
        assertTrue(updated.any { it.id == "ops" })
    }

    @Test
    fun `upsert con bumpLastUsed actualiza el timestamp`() {
        val before = System.currentTimeMillis()
        val initial = emptyList<SavedConnection>()
        val candidate = entry(lastUsed = 0L)

        val updated = SavedConnections.upsert(initial, candidate, bumpLastUsed = true)

        assertTrue(updated.single().lastUsedAtMillis >= before)
    }

    @Test
    fun `removeById quita solo la entrada con id matching`() {
        val list = listOf(entry(id = "a"), entry(id = "b"), entry(id = "c"))
        val updated = SavedConnections.removeById(list, "b")
        assertEquals(listOf("a", "c"), updated.map { it.id })
    }

    @Test
    fun `removeByHost quita todas las entradas para host port dados`() {
        val list = listOf(
            entry(id = "admin", host = "10.0.0.1", username = "admin"),
            entry(id = "ops", host = "10.0.0.1", username = "ops"),
            entry(id = "other", host = "10.0.0.2"),
        )
        val updated = SavedConnections.removeByHost(list, "SSH", "10.0.0.1", 22)
        assertEquals(listOf("other"), updated.map { it.id })
    }

    @Test
    fun `roundtrip JSON preserva password cifrada y campos opcionales`() {
        val encrypted = SecretCipher.encrypt("super-secret-pwd")
        val original = AppSettings(
            savedConnections = listOf(
                entry(
                    id = "pwd-entry",
                    username = "admin",
                    authKind = SavedAuthKind.PASSWORD,
                    secret = encrypted,
                ),
                entry(
                    id = "key-entry",
                    username = "ops",
                    authKind = SavedAuthKind.SSH_KEY,
                    keyPath = "/home/ops/.ssh/id_ed25519",
                    secret = SecretCipher.encrypt("passphrase"),
                ),
            ),
        )

        val json = mapper.writeValueAsString(original)
        val restored = mapper.readValue(json, AppSettings::class.java)

        assertEquals(2, restored.savedConnections.size)
        val pwd = restored.savedConnections.first { it.id == "pwd-entry" }
        assertEquals(SavedAuthKind.PASSWORD, pwd.authKind)
        assertEquals("super-secret-pwd", SecretCipher.decrypt(pwd.secret!!))
        val key = restored.savedConnections.first { it.id == "key-entry" }
        assertEquals(SavedAuthKind.SSH_KEY, key.authKind)
        assertEquals("/home/ops/.ssh/id_ed25519", key.keyPath)
        assertEquals("passphrase", SecretCipher.decrypt(key.secret!!))
    }

    @Test
    fun `roundtrip JSON con savedConnections vacío preserva default`() {
        val original = AppSettings()
        val json = mapper.writeValueAsString(original)
        val restored = mapper.readValue(json, AppSettings::class.java)
        assertTrue(restored.savedConnections.isEmpty())
    }

    @Test
    fun `displayLabel cae al user host port cuando label esta vacio`() {
        val noLabel = entry()
        assertEquals("admin@10.0.0.1:22", noLabel.displayLabel())
    }

    @Test
    fun `displayLabel devuelve label cuando esta presente`() {
        val withLabel = entry().copy(label = "Router Core")
        assertEquals("Router Core", withLabel.displayLabel())
    }

    @Test
    fun `roundtrip JSON preserva label custom`() {
        val original = AppSettings(
            savedConnections = listOf(entry().copy(label = "Switch Piso 3")),
        )
        val json = mapper.writeValueAsString(original)
        val restored = mapper.readValue(json, AppSettings::class.java)
        assertEquals("Switch Piso 3", restored.savedConnections.single().label)
    }

    @Test
    fun `entries legacy sin label deserializan con string vacio`() {
        // Simula settings.json viejos (pre-feature) que no tienen el campo `label`.
        val legacy = """
            {
              "savedConnections": [
                {"id":"x","protocol":"SSH","host":"h","port":22,"username":"u",
                 "authKind":"NONE","lastUsedAtMillis":0}
              ]
            }
        """.trimIndent()
        val restored = mapper.readValue(legacy, AppSettings::class.java)
        assertEquals("", restored.savedConnections.single().label)
    }
}
