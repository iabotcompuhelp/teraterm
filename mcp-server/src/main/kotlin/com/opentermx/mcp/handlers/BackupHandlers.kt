package com.opentermx.mcp.handlers

import com.opentermx.mcp.backups.BackupCaptureSource
import com.opentermx.mcp.backups.BackupService
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions

class BackupDeviceConfigHandler(
    private val service: BackupService,
    private val captureSource: BackupCaptureSource,
) : ToolHandler {
    override val definition: ToolDef = ToolDefinitions.BACKUP_DEVICE_CONFIG

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val requestedAlias = Args.optionalString(args, "deviceAlias")
        val sessionId = Args.optionalString(args, "sessionId")
        if (requestedAlias == null && sessionId == null) throw McpToolException(
            McpToolException.ErrorCode.INVALID_ARGUMENT, "se requiere `deviceAlias` o `sessionId`",
        )
        val deviceKey = requestedAlias ?: sessionId?.let {
            com.opentermx.common.ai.SessionRegistry.metadataOf(com.opentermx.common.session.SessionId(it))?.host
                ?: "session-$it"
        }!!
        val captured = runCatching { captureSource.capture(requestedAlias, sessionId) }.getOrElse {
            throw McpToolException(
                McpToolException.ErrorCode.UNAVAILABLE,
                "no se pudo capturar la configuración de `$deviceKey`: ${it.message ?: it.javaClass.simpleName}",
            )
        }
        val backup = service.store(
            deviceAlias = deviceKey,
            vendor = captured.vendor,
            configuration = captured.content,
            source = captured.source,
        )
        return backup.toOutput()
    }
}

class ListDeviceBackupsHandler(private val service: BackupService) : ToolHandler {
    override val definition: ToolDef = ToolDefinitions.LIST_DEVICE_BACKUPS

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val deviceAlias = Args.requireString(args, "deviceAlias")
        return linkedMapOf(
            "deviceAlias" to deviceAlias,
            "backups" to service.list(deviceAlias).map { it.toOutput() },
        )
    }
}

class VerifyDeviceBackupHandler(private val service: BackupService) : ToolHandler {
    override val definition: ToolDef = ToolDefinitions.VERIFY_DEVICE_BACKUP

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val deviceAlias = Args.requireString(args, "deviceAlias")
        val backupId = Args.requireString(args, "backupId")
        val result = service.verify(deviceAlias, backupId) ?: throw McpToolException(
            McpToolException.ErrorCode.NOT_FOUND,
            "backup `$backupId` no encontrado para `$deviceAlias`",
        )
        return linkedMapOf(
            "backupId" to result.backupId,
            "valid" to result.valid,
            "encryptedContentValid" to result.encryptedContentValid,
            "redactedContentValid" to result.redactedContentValid,
        )
    }
}

class CompareDeviceBackupHandler(private val service: BackupService) : ToolHandler {
    override val definition: ToolDef = ToolDefinitions.COMPARE_DEVICE_BACKUP

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val deviceAlias = Args.requireString(args, "deviceAlias")
        val baseBackupId = Args.requireString(args, "baseBackupId")
        val targetBackupId = Args.requireString(args, "targetBackupId")
        val result = runCatching { service.compare(deviceAlias, baseBackupId, targetBackupId) }.getOrElse {
            throw McpToolException(McpToolException.ErrorCode.UNAVAILABLE, it.message ?: "no se pudo comparar")
        } ?: throw McpToolException(
            McpToolException.ErrorCode.NOT_FOUND,
            "uno de los backups no existe para `$deviceAlias`",
        )
        return linkedMapOf(
            "baseBackupId" to result.baseBackupId,
            "targetBackupId" to result.targetBackupId,
            "identical" to result.identical,
            "addedLines" to result.addedLines,
            "removedLines" to result.removedLines,
            "baseRedactedSha256" to result.baseRedactedSha256,
            "targetRedactedSha256" to result.targetRedactedSha256,
        )
    }
}

class ProposeRestoreBackupHandler(
    private val service: com.opentermx.mcp.backups.RestoreProposalService,
) : ToolHandler {
    override val definition: ToolDef = ToolDefinitions.PROPOSE_RESTORE_BACKUP

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val deviceAlias = Args.requireString(args, "deviceAlias")
        val backupId = Args.requireString(args, "backupId")
        val rationale = Args.requireString(args, "rationale")
        val proposal = try {
            service.propose(deviceAlias, backupId, rationale)
        } catch (_: NoSuchElementException) {
            throw McpToolException(McpToolException.ErrorCode.NOT_FOUND, "backup `$backupId` no encontrado")
        } catch (e: IllegalArgumentException) {
            throw McpToolException(McpToolException.ErrorCode.INVALID_ARGUMENT, e.message ?: "propuesta inválida")
        }
        return linkedMapOf(
            "proposalId" to proposal.proposalId,
            "deviceAlias" to proposal.deviceAlias,
            "backupId" to proposal.backupId,
            "status" to proposal.status,
            "executionAvailable" to proposal.executionAvailable,
            "createdAt" to proposal.createdAt,
        )
    }
}

class GetRestoreProposalHandler(
    private val service: com.opentermx.mcp.backups.RestoreProposalService,
) : ToolHandler {
    override val definition: ToolDef = ToolDefinitions.GET_RESTORE_PROPOSAL

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val id = Args.requireString(args, "proposalId")
        val proposal = service.load(id) ?: throw McpToolException(
            McpToolException.ErrorCode.NOT_FOUND, "propuesta `$id` no encontrada",
        )
        return proposal.toOutput()
    }
}

class ReviewRestoreProposalHandler(
    private val service: com.opentermx.mcp.backups.RestoreProposalService,
    private val approvalGate: com.opentermx.mcp.security.ApprovalGate,
) : ToolHandler {
    override val definition: ToolDef = ToolDefinitions.REVIEW_RESTORE_PROPOSAL

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val id = Args.requireString(args, "proposalId")
        val proposal = service.load(id) ?: throw McpToolException(
            McpToolException.ErrorCode.NOT_FOUND, "propuesta `$id` no encontrada",
        )
        if (proposal.status != "PENDING_APPROVAL") throw McpToolException(
            McpToolException.ErrorCode.INVALID_ARGUMENT, "la propuesta ya fue decidida: ${proposal.status}",
        )
        val summary = listOf(
            "review_restore_proposal: ${proposal.proposalId}",
            "device: ${proposal.deviceAlias}",
            "backup: ${proposal.backupId}",
            "rationale: ${proposal.rationale}",
            "executionAvailable: false",
        )
        val decision = approvalGate.reviewCommands(
            prompt = "Revisión de propuesta de restauración (NO ejecuta cambios)",
            vendor = com.opentermx.ai.context.Vendor.UNKNOWN,
            classifications = summary.map {
                com.opentermx.ai.safety.ClassifiedCommand(it, com.opentermx.ai.safety.RiskLevel.CONFIG)
            },
        )
        val decided = service.decide(
            id, decision is com.opentermx.mcp.security.ApprovalDecision.Approve,
        )
        return decided.toOutput()
    }
}

private fun com.opentermx.mcp.backups.RestoreProposal.toOutput(): Map<String, Any?> = linkedMapOf(
    "proposalId" to proposalId,
    "deviceAlias" to deviceAlias,
    "backupId" to backupId,
    "rationale" to rationale,
    "status" to status,
    "executionAvailable" to executionAvailable,
    "createdAt" to createdAt,
    "decidedAt" to decidedAt,
)

class PrepareRestoreBackupHandler(
    private val service: com.opentermx.mcp.backups.RestoreProposalService,
) : ToolHandler {
    override val definition: ToolDef = ToolDefinitions.PREPARE_RESTORE_BACKUP

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val proposalId = Args.requireString(args, "proposalId")
        val plan = try {
            service.prepare(proposalId)
        } catch (_: NoSuchElementException) {
            throw McpToolException(McpToolException.ErrorCode.NOT_FOUND, "propuesta o backup no encontrado")
        } catch (e: IllegalArgumentException) {
            throw McpToolException(McpToolException.ErrorCode.INVALID_ARGUMENT, e.message ?: "preflight rechazado")
        }
        return linkedMapOf(
            "planId" to plan.planId,
            "proposalId" to plan.proposalId,
            "deviceAlias" to plan.deviceAlias,
            "backupId" to plan.backupId,
            "status" to plan.status,
            "executionAvailable" to plan.executionAvailable,
            "requiredSteps" to plan.requiredSteps,
            "createdAt" to plan.createdAt,
        )
    }
}

class CapturePreRestoreSnapshotHandler(
    private val restoreService: com.opentermx.mcp.backups.RestoreProposalService,
    private val backupService: BackupService,
    private val captureSource: BackupCaptureSource,
) : ToolHandler {
    override val definition: ToolDef = ToolDefinitions.CAPTURE_PRE_RESTORE_SNAPSHOT

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val planId = Args.requireString(args, "planId")
        val sessionId = Args.requireString(args, "sessionId")
        val plan = restoreService.loadPlan(planId) ?: throw McpToolException(
            McpToolException.ErrorCode.NOT_FOUND, "plan `$planId` no encontrado",
        )
        if (plan.status != "PREPARED_BLOCKED") throw McpToolException(
            McpToolException.ErrorCode.INVALID_ARGUMENT, "plan en estado `${plan.status}`",
        )
        val captured = runCatching { captureSource.capture(null, sessionId) }.getOrElse {
            throw McpToolException(McpToolException.ErrorCode.UNAVAILABLE, it.message ?: "captura fallida")
        }
        val backup = backupService.store(plan.deviceAlias, captured.vendor, captured.content, captured.source)
        val updated = restoreService.attachPreRestoreSnapshot(planId, backup.id)
        return linkedMapOf(
            "planId" to updated.planId,
            "status" to updated.status,
            "executionAvailable" to updated.executionAvailable,
            "preRestoreBackupId" to updated.preRestoreBackupId,
            "preSnapshotCapturedAt" to updated.preSnapshotCapturedAt,
        )
    }
}

class ValidateRestoreTargetHandler(
    private val service: com.opentermx.mcp.backups.RestoreProposalService,
) : ToolHandler {
    override val definition: ToolDef = ToolDefinitions.VALIDATE_RESTORE_TARGET

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val planId = Args.requireString(args, "planId")
        val plan = try {
            service.validateRestoreTarget(planId)
        } catch (_: NoSuchElementException) {
            throw McpToolException(McpToolException.ErrorCode.NOT_FOUND, "plan o backup no encontrado")
        } catch (e: IllegalArgumentException) {
            throw McpToolException(McpToolException.ErrorCode.INVALID_ARGUMENT, e.message ?: "validación rechazada")
        }
        return linkedMapOf(
            "planId" to plan.planId,
            "status" to plan.status,
            "executionAvailable" to plan.executionAvailable,
            "targetBackupValid" to plan.targetBackupValid,
            "preRestoreBackupValid" to plan.preRestoreBackupValid,
            "vendorCompatible" to plan.vendorCompatible,
            "configurationIdentical" to plan.configurationIdentical,
            "addedLines" to plan.addedLines,
            "removedLines" to plan.removedLines,
            "targetValidatedAt" to plan.targetValidatedAt,
        )
    }
}

class CompleteRestoreNoChangeHandler(
    private val service: com.opentermx.mcp.backups.RestoreProposalService,
) : ToolHandler {
    override val definition: ToolDef = ToolDefinitions.COMPLETE_RESTORE_NO_CHANGE

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val planId = Args.requireString(args, "planId")
        val plan = try {
            service.completeNoChange(planId)
        } catch (_: NoSuchElementException) {
            throw McpToolException(McpToolException.ErrorCode.NOT_FOUND, "plan no encontrado")
        } catch (e: IllegalArgumentException) {
            throw McpToolException(McpToolException.ErrorCode.INVALID_ARGUMENT, e.message ?: "cierre rechazado")
        }
        return linkedMapOf(
            "planId" to plan.planId,
            "status" to plan.status,
            "executionAvailable" to plan.executionAvailable,
            "completionReason" to plan.completionReason,
            "completedAt" to plan.completedAt,
        )
    }
}

private fun com.opentermx.mcp.backups.DeviceBackup.toOutput(): Map<String, Any?> = linkedMapOf(
    "backupId" to id,
    "deviceAlias" to deviceAlias,
    "vendor" to vendor,
    "capturedAt" to capturedAt,
    "source" to source,
    "fullContentSha256" to fullContentSha256,
    "redactedContentSha256" to redactedContentSha256,
)
