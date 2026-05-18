package com.opentermx.mcp.snapshots

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RollbackProposerTest {

    private fun snap(content: String) = Snapshot(
        id = "x", operationId = null, sessionId = "s", deviceAlias = null,
        snapshotType = "running_config", timestampMillis = 0L,
        contentHash = Snapshot.hashOf(content), content = content,
    )

    @Test
    fun `idénticos devuelve plan vacío`() {
        val a = snap("a\nb")
        val b = snap("a\nb")
        val plan = RollbackProposer.propose(a, b, "cisco_ios")
        assertTrue(plan.commands.isEmpty())
        assertTrue(plan.notes.first().contains("idénticos"))
    }

    @Test
    fun `Cisco IOS — línea agregada se revierte con no`() {
        val before = snap("interface Gi0/1")
        val after = snap("interface Gi0/1\n description LAN")
        val plan = RollbackProposer.propose(before, after, "cisco_ios")
        assertTrue(plan.supported)
        assertTrue(plan.commands.any { it.startsWith("no description LAN") },
            "commands real: ${plan.commands}")
    }

    @Test
    fun `Cisco IOS — línea con no previo se invierte quitando el no`() {
        val before = snap("interface Gi0/1")
        val after = snap("interface Gi0/1\n no shutdown")
        val plan = RollbackProposer.propose(before, after, "cisco_ios")
        // El rollback de "no shutdown" debe ser "shutdown".
        assertTrue(plan.commands.any { it.trim() == "shutdown" }, "commands real: ${plan.commands}")
    }

    @Test
    fun `Cisco IOS — línea removida se reaplica`() {
        val before = snap("hostname router-old")
        val after = snap("hostname router-new")
        val plan = RollbackProposer.propose(before, after, "cisco_ios")
        assertTrue(plan.commands.any { it.contains("hostname router-old") },
            "commands real: ${plan.commands}")
        // También revertir el agregado: "no hostname router-new".
        assertTrue(plan.commands.any { it.contains("no hostname router-new") })
    }

    @Test
    fun `deviceType no soportado marca supported=false`() {
        val before = snap("a")
        val after = snap("b")
        val plan = RollbackProposer.propose(before, after, "exotic_os")
        assertFalse(plan.supported)
        assertTrue(plan.commands.isEmpty())
        assertTrue(plan.notes.any { it.contains("no soportada") })
    }
}
