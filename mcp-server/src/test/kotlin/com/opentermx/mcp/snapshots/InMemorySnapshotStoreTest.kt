package com.opentermx.mcp.snapshots

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InMemorySnapshotStoreTest {

    private fun snap(id: String, opId: String?, deviceAlias: String?, sessionId: String) = Snapshot(
        id = id, operationId = opId, sessionId = sessionId, deviceAlias = deviceAlias,
        snapshotType = "x", timestampMillis = 0L, contentHash = "h", content = "c",
    )

    @Test
    fun `save y load roundtrip`() {
        val s = InMemorySnapshotStore()
        s.save(snap("s1", "op1", "d1", "sess1"))
        assertEquals("s1", s.load("s1")?.id)
        assertNull(s.load("inexistente"))
    }

    @Test
    fun `listForOperation filtra por opId`() {
        val s = InMemorySnapshotStore()
        s.save(snap("a", "op1", null, "x"))
        s.save(snap("b", "op2", null, "x"))
        s.save(snap("c", "op1", null, "x"))
        val r = s.listForOperation("op1").map { it.id }.toSet()
        assertEquals(setOf("a", "c"), r)
    }

    @Test
    fun `listForDevice combina filtros AND`() {
        val s = InMemorySnapshotStore()
        s.save(snap("a", "op1", "dA", "sess1"))
        s.save(snap("b", "op1", "dB", "sess1"))
        s.save(snap("c", "op2", "dA", "sess2"))

        assertEquals(setOf("a"),
            s.listForDevice("op1", "dA", null).map { it.id }.toSet())
        assertEquals(setOf("a", "c"),
            s.listForDevice(null, "dA", null).map { it.id }.toSet())
        assertEquals(setOf("b"),
            s.listForDevice(null, null, "sess1").filter { it.deviceAlias == "dB" }.map { it.id }.toSet())
    }
}
