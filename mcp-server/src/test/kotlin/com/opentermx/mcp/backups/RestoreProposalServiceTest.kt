package com.opentermx.mcp.backups

import com.opentermx.ai.context.Vendor
import java.nio.file.Path
import java.time.Instant
import javax.crypto.KeyGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class RestoreProposalServiceTest {
    @TempDir lateinit var root: Path

    @Test
    fun `crea ticket durable sin habilitar ejecucion`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val backups = BackupService(root.resolve("backups"), key)
        val backup = backups.store("COM6", Vendor.ARUBA_OS, "hostname 6300", "adapter:CLI_SSH")
        val service = RestoreProposalService(
            backups, root.resolve("proposals"), now = { Instant.parse("2026-08-24T15:00:00Z") },
        )
        val proposal = service.propose("COM6", backup.id, "recuperación autorizada")
        assertEquals("PENDING_APPROVAL", proposal.status)
        assertFalse(proposal.executionAvailable)
        assertNotNull(service.load(proposal.proposalId))
        val decided = service.decide(proposal.proposalId, approved = true)
        assertEquals("APPROVED", decided.status)
        assertFalse(decided.executionAvailable)
        assertEquals("APPROVED", service.load(proposal.proposalId)!!.status)
        val plan = service.prepare(proposal.proposalId)
        assertEquals("PREPARED_BLOCKED", plan.status)
        assertFalse(plan.executionAvailable)
        assertEquals(5, plan.requiredSteps.size)
        val pre = backups.store("COM6", Vendor.ARUBA_OS, "hostname 6300", "adapter:CLI_SSH")
        val updated = service.attachPreRestoreSnapshot(plan.planId, pre.id)
        assertEquals("PRE_SNAPSHOT_CAPTURED", updated.status)
        assertEquals(pre.id, updated.preRestoreBackupId)
        assertFalse(updated.executionAvailable)
        val validated = service.validateRestoreTarget(plan.planId)
        assertEquals("TARGET_VALIDATED_BLOCKED", validated.status)
        assertEquals(true, validated.targetBackupValid)
        assertEquals(true, validated.preRestoreBackupValid)
        assertEquals(true, validated.vendorCompatible)
        assertEquals(true, validated.configurationIdentical)
        assertEquals(0, validated.addedLines)
        assertEquals(0, validated.removedLines)
        assertFalse(validated.executionAvailable)
        val completed = service.completeNoChange(plan.planId)
        assertEquals("COMPLETED_NO_CHANGE", completed.status)
        assertEquals("current_configuration_matches_target_backup", completed.completionReason)
        assertNotNull(completed.completedAt)
        assertFalse(completed.executionAvailable)
    }

    @Test
    fun `rechaza backup inexistente`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val service = RestoreProposalService(BackupService(root.resolve("backups"), key), root.resolve("proposals"))
        assertThrows<NoSuchElementException> { service.propose("COM6", "missing", "prueba") }
    }

    @Test
    fun `preflight rechaza propuesta pendiente`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val backups = BackupService(root.resolve("backups-pending"), key)
        val backup = backups.store("COM6", Vendor.ARUBA_OS, "hostname 6300", "adapter:CLI_SSH")
        val service = RestoreProposalService(backups, root.resolve("proposals-pending"))
        val proposal = service.propose("COM6", backup.id, "pendiente")
        assertThrows<IllegalArgumentException> { service.prepare(proposal.proposalId) }
    }

    @Test
    fun `validacion rechaza fabricante diferente`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val backups = BackupService(root.resolve("backups-vendor"), key)
        val target = backups.store("edge", Vendor.ARUBA_OS, "hostname 6300", "adapter:CLI_SERIAL")
        val service = RestoreProposalService(backups, root.resolve("proposals-vendor"))
        val proposal = service.propose("edge", target.id, "prueba de compatibilidad")
        service.decide(proposal.proposalId, approved = true)
        val plan = service.prepare(proposal.proposalId)
        val pre = backups.store("edge", Vendor.CISCO_IOS, "hostname router", "adapter:CLI_SERIAL")
        service.attachPreRestoreSnapshot(plan.planId, pre.id)
        assertThrows<IllegalArgumentException> { service.validateRestoreTarget(plan.planId) }
        assertEquals("PRE_SNAPSHOT_CAPTURED", service.loadPlan(plan.planId)!!.status)
    }

    @Test
    fun `cierre sin cambios rechaza configuraciones diferentes`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val backups = BackupService(root.resolve("backups-diff"), key)
        val target = backups.store("edge", Vendor.ARUBA_OS, "hostname target", "adapter:CLI_SERIAL")
        val service = RestoreProposalService(backups, root.resolve("proposals-diff"))
        val proposal = service.propose("edge", target.id, "prueba de diferencia")
        service.decide(proposal.proposalId, approved = true)
        val plan = service.prepare(proposal.proposalId)
        val pre = backups.store("edge", Vendor.ARUBA_OS, "hostname current", "adapter:CLI_SERIAL")
        service.attachPreRestoreSnapshot(plan.planId, pre.id)
        val validated = service.validateRestoreTarget(plan.planId)
        assertFalse(validated.configurationIdentical!!)
        assertThrows<IllegalArgumentException> { service.completeNoChange(plan.planId) }
        assertEquals("TARGET_VALIDATED_BLOCKED", service.loadPlan(plan.planId)!!.status)
    }
}
