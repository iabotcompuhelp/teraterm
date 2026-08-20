package com.opentermx.mcp.operation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import com.opentermx.mcp.handlers.ExportOperationHandoffHandler
import com.opentermx.mcp.snapshots.InMemorySnapshotStore
import com.opentermx.mcp.snapshots.Snapshot
import kotlinx.coroutines.runBlocking

class OperationHandoffTest {
    private fun context(id: String = "op-handoff") = OperationContext(
        operation = OperationMeta(id = id, description = "Diagnosticar core"),
        scope = OperationScope(devices = listOf("core-1"), forbiddenCommands = listOf("reload")),
    )

    @Test
    fun `journal redacta secretos y conserva secuencia`() {
        val store = InMemoryOperationStore()
        val registry = OperationRegistry(store)
        val record = registry.start("model-a", context(), nowMillis = 10)
        registry.appendEvent(
            record.operationId,
            OperationEventType.TOOL_SUCCEEDED,
            "model-a",
            "corr-1",
            "inspect_session",
            "SUCCEEDED",
            mapOf("apiKey" to "super-secret", "nested" to mapOf("password" to "hidden")),
            nowMillis = 20,
        )

        val entries = registry.journal(record.operationId)
        assertEquals(listOf(1L, 2L), entries.map { it.sequence })
        assertEquals("[REDACTED]", entries.last().payload["apiKey"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("[REDACTED]", (entries.last().payload["nested"] as Map<String, Any?>)["password"])
    }

    @Test
    fun `handoff es determinista salvo timestamp y persiste`() {
        val store = InMemoryOperationStore()
        val registry = OperationRegistry(store)
        val record = registry.start("model-a", context(), nowMillis = 10)
        registry.appendEvent(
            record.operationId, OperationEventType.TOOL_SUCCEEDED, "model-a", "corr-1",
            "list_sessions", "SUCCEEDED", nowMillis = 20,
        )

        val first = registry.exportHandoff(
            record.operationId, "openai", "ollama", "timeout", 900, nowMillis = 30,
        )
        val second = registry.exportHandoff(
            record.operationId, "openai", "ollama", "timeout", 900, nowMillis = 30,
        )

        assertEquals(first, second)
        assertEquals("1.0", first.schemaVersion)
        assertEquals("ollama", first.targetProvider)
        assertTrue(first.factualSummary.contains("1 tools completadas"))
        assertEquals(2, store.savedHandoffs(record.operationId).size)
    }

    @Test
    fun `restart recupera journal y permite rebind a otro modelo`(@TempDir root: Path) {
        val first = OperationRegistry(FsOperationStore(root))
        val record = first.start("model-a", context("op-recovery"), nowMillis = 10)
        first.appendEvent(
            record.operationId, OperationEventType.TOOL_SUCCEEDED, "model-a", "corr-1",
            "inspect_session", "SUCCEEDED", nowMillis = 20,
        )

        val recovered = OperationRegistry(FsOperationStore(root))
        val rebound = recovered.resume("model-b", record.operationId, nowMillis = 30)

        assertEquals(record.operationId, rebound.operationId)
        assertEquals(record.operationId, recovered.forSessionKey("model-b")?.operationId)
        assertEquals(listOf(1L, 2L, 3L), recovered.journal(record.operationId).map { it.sequence })
        assertTrue(Files.isRegularFile(root.resolve(record.operationId).resolve("journal.jsonl")))
        val closed = recovered.end("model-b", record.operationId, nowMillis = 40)
        assertEquals(record.operationId, closed["operationId"])
    }

    @Test
    fun `handoff serializado no contiene secretos`() {
        val store = InMemoryOperationStore()
        val registry = OperationRegistry(store)
        val record = registry.start("model-a", context(), nowMillis = 10)
        registry.appendEvent(
            record.operationId, OperationEventType.TOOL_SUCCEEDED, "model-a", "corr-1",
            "tool", "SUCCEEDED", mapOf("token" to "never-export"), nowMillis = 20,
        )
        val json = ObjectMapper().registerKotlinModule().writeValueAsString(
            registry.exportHandoff(record.operationId, nowMillis = 30),
        )
        assertFalse(json.contains("never-export"))
        assertTrue(json.contains("[REDACTED]"))
    }

    @Test
    fun `export handler conecta snapshots por hash sin copiar contenido`() = runBlocking {
        val operationStore = InMemoryOperationStore()
        val registry = OperationRegistry(operationStore)
        val record = registry.start("model-a", context(), nowMillis = 10)
        val snapshots = InMemorySnapshotStore()
        snapshots.save(
            Snapshot(
                id = "snap-proof", operationId = record.operationId, sessionId = "s1",
                deviceAlias = "core-1", snapshotType = "running_config", timestampMillis = 20,
                contentHash = "a".repeat(64), content = "secret running configuration", label = "pre-change",
            ),
        )

        val payload = ExportOperationHandoffHandler(registry, snapshots).invoke(emptyMap(), "model-a")
        @Suppress("UNCHECKED_CAST")
        val evidence = (payload["evidence"] as List<Map<String, Any?>>).single()
        val json = ObjectMapper().registerKotlinModule().writeValueAsString(payload)

        assertEquals("snap-proof", evidence["id"])
        assertEquals("a".repeat(64), evidence["sha256"])
        assertEquals("core-1", evidence["deviceAlias"])
        assertFalse(json.contains("secret running configuration"))
    }
}
