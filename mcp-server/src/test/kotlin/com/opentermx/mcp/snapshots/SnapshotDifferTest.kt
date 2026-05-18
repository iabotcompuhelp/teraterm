package com.opentermx.mcp.snapshots

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SnapshotDifferTest {

    private fun snap(id: String, content: String) = Snapshot(
        id = id, operationId = null, sessionId = "s1", deviceAlias = null,
        snapshotType = "running_config", timestampMillis = 0L,
        contentHash = Snapshot.hashOf(content), content = content,
    )

    @Test
    fun `snapshots idénticos devuelven sin cambios`() {
        val a = snap("a", "line1\nline2")
        val b = snap("b", "line1\nline2")
        val diff = SnapshotDiffer.diff(a, b)
        assertTrue(diff.addedLines.isEmpty() && diff.removedLines.isEmpty())
        assertEquals("Sin cambios (hash idéntico)", diff.summary)
    }

    @Test
    fun `agregados y removidos detectados sin agrupación cuando deviceType es null`() {
        val before = snap("a", "a\nb\nc")
        val after = snap("b", "a\nb\nx\ny")
        val diff = SnapshotDiffer.diff(before, after, deviceType = null)
        assertEquals(listOf("x", "y"), diff.addedLines)
        assertEquals(listOf("c"), diff.removedLines)
        assertTrue(diff.sections.isEmpty(), "sin deviceType reconocido no debería agrupar")
    }

    @Test
    fun `Cisco IOS agrupa cambios por sección de config`() {
        val before = snap("a", """
            hostname old-name
            !
            interface Gi0/1
             ip address 10.0.0.1 255.255.255.0
             no shutdown
            !
            router ospf 1
             network 10.0.0.0 0.0.0.255 area 0
        """.trimIndent())
        val after = snap("b", """
            hostname new-name
            !
            interface Gi0/1
             ip address 10.0.0.1 255.255.255.0
             description LAN
             no shutdown
            !
            interface Gi0/2
             ip address 192.168.1.1 255.255.255.0
            !
            router ospf 1
             network 10.0.0.0 0.0.0.255 area 0
        """.trimIndent())

        val diff = SnapshotDiffer.diff(before, after, deviceType = "cisco_ios")
        // Hay sección modificada (Gi0/1) y agregada (Gi0/2).
        val gi01 = diff.sections.first { it.header == "interface Gi0/1" }
        assertEquals(SnapshotDiffer.SectionChange.MODIFIED, gi01.change)
        assertTrue(gi01.addedLines.any { "description LAN" in it })

        val gi02 = diff.sections.first { it.header == "interface Gi0/2" }
        assertEquals(SnapshotDiffer.SectionChange.ADDED, gi02.change)

        // hostname también está modificada (cambió a otro valor).
        val hostnameChanged = diff.addedLines.any { it == "hostname new-name" } &&
            diff.removedLines.any { it == "hostname old-name" }
        assertTrue(hostnameChanged)
    }

    @Test
    fun `summary refleja conteo de added removed y secciones`() {
        val before = snap("a", "interface Gi0/1\n ip address 10.0.0.1 255.0.0.0")
        val after = snap("b", "interface Gi0/1\n ip address 10.0.0.2 255.0.0.0")
        val diff = SnapshotDiffer.diff(before, after, deviceType = "cisco_ios")
        assertTrue(diff.summary.contains("+1") && diff.summary.contains("-1"))
        assertTrue(diff.summary.contains("sección"))
    }

    @Test
    fun `deviceType desconocido no produce secciones`() {
        val before = snap("a", "a\nb")
        val after = snap("b", "a\nc")
        val diff = SnapshotDiffer.diff(before, after, deviceType = "alpine_linux")
        assertTrue(diff.sections.isEmpty())
        assertFalse(diff.summary.contains("sección"))
    }
}
