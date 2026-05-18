package com.opentermx.mcp.handlers

import com.opentermx.ai.audit.AiAuditEntry
import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.ai.context.Vendor
import com.opentermx.ai.context.VendorDetector
import com.opentermx.ai.safety.CredentialRedactor
import com.opentermx.ai.safety.RiskClassifier
import com.opentermx.ai.safety.RiskLevel
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.NOT_FOUND
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.UNAVAILABLE
import com.opentermx.mcp.security.ApprovalDecision
import com.opentermx.mcp.security.ApprovalGate
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions
import java.util.UUID

/**
 * Handler de la tool MUTATIVA `propose_commands`.
 *
 * Flujo:
 *  1. Validar que `sessionId` exista en el [SessionRegistry] y tenga un `CommandSink`
 *     (sin sink, no hay forma de enviar comandos: error).
 *  2. Detectar el vendor a partir del buffer reciente (`VendorDetector`).
 *  3. Clasificar los comandos con [RiskClassifier].
 *  4. Pedir aprobación al operador vía [ApprovalGate]. Si rechaza, no se ejecuta nada;
 *     se devuelve `approved=false` y se loguea en [AiAuditLog].
 *  5. Si aprueba, inyectar los comandos línea por línea por el sink, contando
 *     ejecutados y fallidos.
 *  6. Loguear todo (`AiAuditLog`) y devolver el resumen estructurado.
 *
 * La invariante de seguridad NO se negocia: aunque la lista llegue vacía o el operador
 * ya esté frente al panel, el approval gate se invoca siempre.
 */
class ProposeCommandsHandler(
    private val approvalGate: ApprovalGate,
    private val auditLog: AiAuditLog = AiAuditLog(),
    private val injectDelayMillis: Long = DEFAULT_INJECT_DELAY_MILLIS,
    private val redactor: CredentialRedactor = CredentialRedactor(),
    /**
     * Phase 3 Fase 3: registry + secret para verificar `approvalToken` cuando la operation
     * activa exige `require_compliance_approval`. Si ambos son null la verificación se
     * skipea (back-compat con tests previos).
     *
     * Phase 3 Fase 4: cuando la op exige `require_snapshot`, el handler exige también
     * que exista al menos un snapshot previo del sessionId/device antes de ejecutar.
     */
    private val operationRegistry: com.opentermx.mcp.operation.OperationRegistry? = null,
    private val approvalSecretProvider: (() -> ByteArray)? = null,
    private val snapshotStore: com.opentermx.mcp.snapshots.SnapshotStore? = null,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.PROPOSE_COMMANDS

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val sessionIdRaw = Args.requireString(args, "sessionId")
        val commands = Args.requireStringList(args, "commands")
        val rationale = Args.optionalString(args, "rationale").orEmpty()
        val approvalToken = Args.optionalString(args, "approvalToken")
        val deviceAlias = Args.optionalString(args, "deviceAlias")

        // Phase 3 Fase 3: validar approval token contra la operation activa, si la hay y
        // si pide require_compliance_approval. La verificación corre ANTES del approval
        // gate humano — si el token no matchea, ni siquiera mostramos el diálogo.
        if (operationRegistry != null && approvalSecretProvider != null) {
            verifyComplianceTokenIfNeeded(
                operationRegistry, approvalSecretProvider, approvalToken, deviceAlias, commands,
            )
        }

        // Phase 3 Fase 4: verificar que haya snapshot pre-cambio si la op lo exige.
        if (operationRegistry != null && snapshotStore != null) {
            verifyRequireSnapshotIfNeeded(operationRegistry, snapshotStore, sessionIdRaw, deviceAlias)
        }

        val sessionId = SessionId(sessionIdRaw)
        val metadata = SessionRegistry.metadataOf(sessionId)
            ?: throw McpToolException(NOT_FOUND, "Sesión `$sessionIdRaw` no encontrada en el registro")
        val sink = SessionRegistry.sinkOf(sessionId)
            ?: throw McpToolException(UNAVAILABLE, "Sesión `$sessionIdRaw` sin sink: no es inyectable")

        val sample = SessionRegistry.lastLinesOf(sessionId, VENDOR_SAMPLE_LINES).joinToString("\n")
        val vendor = if (sample.isBlank()) Vendor.UNKNOWN else VendorDetector.detect(sample)
        val classifications = RiskClassifier.classify(commands, vendor)
        val riskSummary = riskSummaryOf(classifications.map { it.risk })
        val auditLogId = UUID.randomUUID().toString()

        val decision = approvalGate.reviewCommands(rationale, vendor, classifications)

        return when (decision) {
            is ApprovalDecision.Reject -> {
                logAudit(
                    auditLogId, sessionId, metadata.host, vendor,
                    rationale, commands, classifications.map { it.risk },
                    executed = 0, failed = 0, rejected = true, outputTail = "",
                )
                linkedMapOf(
                    "approved" to false,
                    "executed" to 0,
                    "rejected" to commands.size,
                    "auditLogId" to auditLogId,
                    "output" to null,
                    "riskSummary" to riskSummary,
                )
            }
            is ApprovalDecision.Approve -> {
                val approved = decision.commands
                var executed = 0
                var failed = 0
                for (cmd in approved) {
                    val ok = runCatching { sink.sendLine(cmd) }.getOrDefault(false)
                    if (ok) executed++ else failed++
                    if (injectDelayMillis > 0) {
                        kotlinx.coroutines.delay(injectDelayMillis)
                    }
                }
                val tailRaw = SessionRegistry.lastLinesOf(sessionId, 20).joinToString("\n")
                val tailRedacted = redactor.redact(tailRaw, vendor)
                logAudit(
                    auditLogId, sessionId, metadata.host, vendor,
                    rationale, approved, decision.risks,
                    executed = executed, failed = failed, rejected = false, outputTail = tailRedacted,
                )
                linkedMapOf(
                    "approved" to true,
                    "executed" to executed,
                    "rejected" to (commands.size - approved.size),
                    "auditLogId" to auditLogId,
                    "output" to tailRedacted,
                    "riskSummary" to riskSummaryOf(decision.risks),
                )
            }
        }
    }

    private fun logAudit(
        auditLogId: String,
        sessionId: SessionId,
        host: String?,
        vendor: Vendor,
        prompt: String,
        commands: List<String>,
        risks: List<RiskLevel>,
        executed: Int,
        failed: Int,
        rejected: Boolean,
        outputTail: String,
    ) {
        auditLog.append(
            AiAuditEntry(
                timestampMillis = System.currentTimeMillis(),
                sessionId = "${sessionId.value}#$auditLogId",
                host = host,
                vendor = vendor.takeIf { it != Vendor.UNKNOWN }?.displayName,
                prompt = prompt.ifBlank { "(mcp propose_commands)" },
                commands = commands,
                commandRisks = risks,
                executedCount = executed,
                skippedCount = (commands.size - executed - failed).coerceAtLeast(0),
                failedCount = failed,
                rejected = rejected,
                outputTail = outputTail,
            )
        )
    }

    private fun riskSummaryOf(risks: List<RiskLevel>): Map<String, Int> = linkedMapOf(
        "safe" to risks.count { it == RiskLevel.SAFE },
        "config" to risks.count { it == RiskLevel.CONFIG },
        "dangerous" to risks.count { it == RiskLevel.DANGEROUS },
    )

    /**
     * Phase 3 Fase 3: verifica el approvalToken cuando la operation activa lo exige.
     * El registry NO sabe qué sessionKey llamó (eso vive en el dispatcher); por eso miramos
     * `forOperationId` de cualquier op que el token reclame. La verificación matchea ese id
     * contra el payload del token. Si el cliente no manda token y la op exige, rechazamos.
     */
    private fun verifyComplianceTokenIfNeeded(
        registry: com.opentermx.mcp.operation.OperationRegistry,
        secretProvider: () -> ByteArray,
        token: String?,
        deviceAlias: String?,
        commands: List<String>,
    ) {
        // Sin token no podemos identificar la op desde el handler; pero si NINGUNA op
        // activa exige approval, no hay nada que verificar. Como el handler no recibe
        // sessionKey hoy, la heurística es: si hay token, lo verificamos; si no hay token
        // y existe AL MENOS una op con require_compliance_approval, exigimos uno. El
        // mensaje de error le indica al cliente cómo proceder.
        if (token.isNullOrBlank()) {
            // Sin token: rechazamos sólo si hay ops con require_compliance_approval. Esto
            // mantiene back-compat con tests/clientes que no usan operations.
            val needsApproval = registry.activeOperationsRequiringComplianceApproval()
            if (needsApproval.isNotEmpty()) {
                throw McpToolException(
                    McpToolException.ErrorCode.INVALID_ARGUMENT,
                    "Esta operation exige approval_token. Llamá `compliance_evaluate` " +
                        "(con rol COMPLIANCE) y pasá el `approvalToken` retornado en este request.",
                )
            }
            return
        }

        // Probamos verificar contra cada operation activa que exija approval. Tomamos la
        // primera match porque el token lleva el operationId concreto.
        val candidates = registry.activeOperationsRequiringComplianceApproval()
        if (candidates.isEmpty()) {
            // Cliente mandó token pero ninguna op lo pide. Lo ignoramos silenciosamente
            // — el approval gate humano sigue siendo la barrera.
            return
        }
        val results = candidates.map { rec ->
            com.opentermx.mcp.security.ApprovalTokens.verify(
                token = token,
                expectedOperationId = rec.operationId,
                expectedDeviceAlias = deviceAlias,
                commands = commands,
                secret = secretProvider(),
            )
        }
        if (results.any { it is com.opentermx.mcp.security.Verification.Valid }) return

        val firstInvalid = results.filterIsInstance<com.opentermx.mcp.security.Verification.Invalid>()
            .firstOrNull()?.reason ?: "approval_token inválido"
        throw McpToolException(
            McpToolException.ErrorCode.INVALID_ARGUMENT,
            firstInvalid,
        )
    }

    /**
     * Phase 3 Fase 4: si alguna op activa exige `require_snapshot`, el handler exige al
     * menos un snapshot previo para el sessionId (o deviceAlias si se provee). El
     * snapshot puede ser de cualquier tipo; lo importante es que exista — el cliente LLM
     * debe haber capturado el "estado pre" antes del cambio.
     */
    private fun verifyRequireSnapshotIfNeeded(
        registry: com.opentermx.mcp.operation.OperationRegistry,
        store: com.opentermx.mcp.snapshots.SnapshotStore,
        sessionId: String,
        deviceAlias: String?,
    ) {
        val candidates = registry.activeOperationsRequiringSnapshot()
        if (candidates.isEmpty()) return
        for (rec in candidates) {
            val existing = store.listForDevice(
                operationId = rec.operationId,
                deviceAlias = deviceAlias,
                sessionId = sessionId,
            )
            if (existing.isNotEmpty()) return
        }
        throw McpToolException(
            McpToolException.ErrorCode.INVALID_ARGUMENT,
            "La operation activa exige `require_snapshot=true`: no hay snapshot previo " +
                "para sessionId=`$sessionId`. Capturalo con `snapshot_create` antes de ejecutar.",
        )
    }

    companion object {
        const val DEFAULT_INJECT_DELAY_MILLIS = 120L
        private const val VENDOR_SAMPLE_LINES = 64
    }
}