package com.opentermx.mcp.handlers

import com.opentermx.ai.context.Vendor
import com.opentermx.ai.safety.RiskLevel
import com.opentermx.mcp.backups.BackupService
import com.opentermx.mcp.backups.RestoreProposalService
import com.opentermx.mcp.security.ApprovalDecision
import com.opentermx.mcp.security.ApprovalGate
import java.nio.file.Path
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RestoreReviewHandlerTest {
    @TempDir lateinit var root: Path

    private fun seeded(): Pair<RestoreProposalService, String> {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val backups = BackupService(root.resolve("backups"), key)
        val backup = backups.store("COM6", Vendor.ARUBA_OS, "hostname 6300", "adapter:CLI_SSH")
        val service = RestoreProposalService(backups, root.resolve("proposals"))
        return service to service.propose("COM6", backup.id, "prueba controlada").proposalId
    }

    @Test
    fun `aprobacion solo cambia estado y no habilita ejecucion`() = runBlocking {
        val (service, id) = seeded()
        val gate = object : ApprovalGate {
            override suspend fun reviewCommands(
                prompt: String,
                vendor: Vendor,
                classifications: List<com.opentermx.ai.safety.ClassifiedCommand>,
            ) = ApprovalDecision.Approve(classifications.map { it.raw }, classifications.map { RiskLevel.CONFIG })
        }
        val result = ReviewRestoreProposalHandler(service, gate).invoke(mapOf("proposalId" to id))
        assertEquals("APPROVED", result["status"])
        assertEquals(false, result["executionAvailable"])
        assertFalse(result.containsKey("content"))
    }

    @Test
    fun `rechazo queda persistido y no habilita ejecucion`() = runBlocking {
        val (service, id) = seeded()
        val gate = object : ApprovalGate {
            override suspend fun reviewCommands(
                prompt: String,
                vendor: Vendor,
                classifications: List<com.opentermx.ai.safety.ClassifiedCommand>,
            ) = ApprovalDecision.Reject
        }
        val result = ReviewRestoreProposalHandler(service, gate).invoke(mapOf("proposalId" to id))
        assertEquals("REJECTED", result["status"])
        assertEquals(false, result["executionAvailable"])
        assertEquals("REJECTED", service.load(id)!!.status)
    }
}
