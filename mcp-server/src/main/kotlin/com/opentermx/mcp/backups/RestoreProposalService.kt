package com.opentermx.mcp.backups

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

data class RestoreProposal(
    val proposalId: String,
    val deviceAlias: String,
    val backupId: String,
    val rationale: String,
    val createdAt: String,
    val status: String = "PENDING_APPROVAL",
    val executionAvailable: Boolean = false,
    val decidedAt: String? = null,
)

data class RestoreExecutionPlan(
    val planId: String,
    val proposalId: String,
    val deviceAlias: String,
    val backupId: String,
    val createdAt: String,
    val status: String = "PREPARED_BLOCKED",
    val executionAvailable: Boolean = false,
    val requiredSteps: List<String> = listOf(
        "capture_pre_restore_snapshot",
        "apply_via_management_adapter",
        "capture_post_restore_snapshot",
        "verify_against_backup_hash",
        "record_audit_result",
    ),
    val preRestoreBackupId: String? = null,
    val preSnapshotCapturedAt: String? = null,
    val targetValidatedAt: String? = null,
    val targetBackupValid: Boolean? = null,
    val preRestoreBackupValid: Boolean? = null,
    val vendorCompatible: Boolean? = null,
    val configurationIdentical: Boolean? = null,
    val addedLines: Int? = null,
    val removedLines: Int? = null,
    val completedAt: String? = null,
    val completionReason: String? = null,
)

/** Persiste propuestas de restauración; deliberadamente no contiene ninguna ruta de ejecución. */
class RestoreProposalService(
    private val backupService: BackupService,
    root: Path,
    private val now: () -> Instant = Instant::now,
) {
    private val root = root.toAbsolutePath().normalize()
    private val mapper = ObjectMapper().registerKotlinModule()

    fun propose(deviceAlias: String, backupId: String, rationale: String): RestoreProposal {
        require(rationale.isNotBlank()) { "rationale no puede estar vacío" }
        val verification = backupService.verify(deviceAlias, backupId)
            ?: throw NoSuchElementException("backup no encontrado")
        require(verification.valid) { "backup inválido; restauración rechazada" }
        val proposal = RestoreProposal(
            proposalId = "restore-" + UUID.randomUUID().toString().take(12),
            deviceAlias = deviceAlias,
            backupId = backupId,
            rationale = rationale,
            createdAt = now().toString(),
        )
        Files.createDirectories(root)
        val target = safePath("${proposal.proposalId}.json")
        val temp = Files.createTempFile(root, ".restore-", ".tmp")
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), proposal)
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
        return proposal
    }

    fun load(proposalId: String): RestoreProposal? {
        val path = safePath("$proposalId.json")
        if (!Files.isRegularFile(path)) return null
        return runCatching { mapper.readValue<RestoreProposal>(path.toFile()) }.getOrNull()
    }

    @Synchronized
    fun decide(proposalId: String, approved: Boolean): RestoreProposal {
        val current = load(proposalId) ?: throw NoSuchElementException("propuesta no encontrada")
        require(current.status == "PENDING_APPROVAL") { "la propuesta ya fue decidida: ${current.status}" }
        val decided = current.copy(
            status = if (approved) "APPROVED" else "REJECTED",
            // Aprobación registra intención humana, pero la ejecución continúa sin existir.
            executionAvailable = false,
            decidedAt = now().toString(),
        )
        val target = safePath("$proposalId.json")
        val temp = Files.createTempFile(root, ".restore-decision-", ".tmp")
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), decided)
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
        return decided
    }

    @Synchronized
    fun prepare(proposalId: String): RestoreExecutionPlan {
        val proposal = load(proposalId) ?: throw NoSuchElementException("propuesta no encontrada")
        require(proposal.status == "APPROVED") { "la propuesta no está aprobada: ${proposal.status}" }
        require(!proposal.executionAvailable) { "estado inseguro: la propuesta no debe habilitar ejecución" }
        val verification = backupService.verify(proposal.deviceAlias, proposal.backupId)
            ?: throw NoSuchElementException("backup no encontrado")
        require(verification.valid) { "backup inválido; preflight rechazado" }
        val plan = RestoreExecutionPlan(
            planId = "restore-plan-" + UUID.randomUUID().toString().take(12),
            proposalId = proposal.proposalId,
            deviceAlias = proposal.deviceAlias,
            backupId = proposal.backupId,
            createdAt = now().toString(),
        )
        val plansRoot = root.resolve("plans").normalize()
        require(plansRoot.startsWith(root)) { "ruta de planes inválida" }
        Files.createDirectories(plansRoot)
        val target = plansRoot.resolve("${plan.planId}.json")
        val temp = Files.createTempFile(plansRoot, ".restore-plan-", ".tmp")
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), plan)
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
        return plan
    }

    fun loadPlan(planId: String): RestoreExecutionPlan? {
        val path = safePlanPath(planId)
        if (!Files.isRegularFile(path)) return null
        return runCatching { mapper.readValue<RestoreExecutionPlan>(path.toFile()) }.getOrNull()
    }

    @Synchronized
    fun attachPreRestoreSnapshot(planId: String, backupId: String): RestoreExecutionPlan {
        val plan = loadPlan(planId) ?: throw NoSuchElementException("plan no encontrado")
        require(plan.status == "PREPARED_BLOCKED") { "el plan no admite snapshot previo: ${plan.status}" }
        val verification = backupService.verify(plan.deviceAlias, backupId)
            ?: throw NoSuchElementException("backup previo no encontrado")
        require(verification.valid) { "snapshot previo inválido" }
        val updated = plan.copy(
            status = "PRE_SNAPSHOT_CAPTURED",
            executionAvailable = false,
            preRestoreBackupId = backupId,
            preSnapshotCapturedAt = now().toString(),
        )
        writePlan(updated)
        return updated
    }

    @Synchronized
    fun validateRestoreTarget(planId: String): RestoreExecutionPlan {
        val plan = loadPlan(planId) ?: throw NoSuchElementException("plan no encontrado")
        require(plan.status == "PRE_SNAPSHOT_CAPTURED") {
            "el plan no admite validación del destino: ${plan.status}"
        }
        val preBackupId = requireNotNull(plan.preRestoreBackupId) { "falta snapshot previo" }
        val target = backupService.list(plan.deviceAlias).firstOrNull { it.id == plan.backupId }
            ?: throw NoSuchElementException("backup objetivo no encontrado")
        val pre = backupService.list(plan.deviceAlias).firstOrNull { it.id == preBackupId }
            ?: throw NoSuchElementException("backup previo no encontrado")
        val targetVerification = backupService.verify(plan.deviceAlias, target.id)
            ?: throw NoSuchElementException("backup objetivo no encontrado")
        val preVerification = backupService.verify(plan.deviceAlias, pre.id)
            ?: throw NoSuchElementException("backup previo no encontrado")
        require(targetVerification.valid) { "backup objetivo inválido" }
        require(preVerification.valid) { "snapshot previo inválido" }
        require(target.vendor == pre.vendor) {
            "fabricante incompatible: objetivo=${target.vendor}, actual=${pre.vendor}"
        }
        val comparison = backupService.compare(plan.deviceAlias, pre.id, target.id)
            ?: throw NoSuchElementException("no se pudo comparar los backups")
        val updated = plan.copy(
            status = "TARGET_VALIDATED_BLOCKED",
            executionAvailable = false,
            targetValidatedAt = now().toString(),
            targetBackupValid = true,
            preRestoreBackupValid = true,
            vendorCompatible = true,
            configurationIdentical = comparison.identical,
            addedLines = comparison.addedLines,
            removedLines = comparison.removedLines,
        )
        writePlan(updated)
        return updated
    }

    @Synchronized
    fun completeNoChange(planId: String): RestoreExecutionPlan {
        val plan = loadPlan(planId) ?: throw NoSuchElementException("plan no encontrado")
        require(plan.status == "TARGET_VALIDATED_BLOCKED") {
            "el plan no admite cierre sin cambios: ${plan.status}"
        }
        require(plan.targetBackupValid == true && plan.preRestoreBackupValid == true) {
            "los respaldos no están validados"
        }
        require(plan.vendorCompatible == true) { "el fabricante no es compatible" }
        require(plan.configurationIdentical == true) {
            "la configuración actual difiere del respaldo objetivo"
        }
        val completed = plan.copy(
            status = "COMPLETED_NO_CHANGE",
            executionAvailable = false,
            completedAt = now().toString(),
            completionReason = "current_configuration_matches_target_backup",
        )
        writePlan(completed)
        return completed
    }

    private fun writePlan(plan: RestoreExecutionPlan) {
        val target = safePlanPath(plan.planId)
        val temp = Files.createTempFile(target.parent, ".restore-plan-update-", ".tmp")
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), plan)
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun safePlanPath(planId: String): Path {
        require(planId.matches(Regex("restore-plan-[A-Za-z0-9-]+"))) { "planId inválido" }
        val plansRoot = root.resolve("plans").normalize()
        require(plansRoot.startsWith(root)) { "ruta de planes inválida" }
        return plansRoot.resolve("$planId.json").normalize().also { require(it.startsWith(plansRoot)) }
    }

    private fun safePath(name: String): Path = root.resolve(name).normalize().also {
        require(it.startsWith(root)) { "ruta de propuesta inválida" }
    }
}
