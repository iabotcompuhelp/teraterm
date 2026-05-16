package com.opentermx.mcp.security

import com.opentermx.ai.context.Vendor
import com.opentermx.ai.safety.ClassifiedCommand
import com.opentermx.ai.safety.RiskLevel

/**
 * Resultado del paso de revisión humana exigido por la invariante de seguridad del módulo
 * (`opentermx-mcp.md` § Decisiones arquitectónicas): cualquier tool MUTATIVA debe pasar por
 * un [ApprovalGate] antes de tocar la conexión del dispositivo.
 */
sealed interface ApprovalDecision {

    /**
     * El operador aprobó (posiblemente editando) la lista [commands]. [risks] viene
     * realineada con la lista final — si el operador re-editó, el clasificador volvió
     * a correr sobre las líneas resultantes.
     */
    data class Approve(val commands: List<String>, val risks: List<RiskLevel>) : ApprovalDecision

    /** El operador rechazó el bloque entero, cerró el diálogo, o hubo timeout. */
    data object Reject : ApprovalDecision
}

/**
 * Abstrae la pantalla de aprobación humana para que `mcp-server` no dependa de JavaFX.
 * La implementación real (`com.opentermx.app.ui.ai.JavaFxApprovalGate`) vive en el módulo
 * `app` y delega en [com.opentermx.app.ui.ai.AiExecuteApprovalDialog]. Los tests usan
 * implementaciones in-memory (auto-aprobar / auto-rechazar).
 *
 * Llamado desde un hilo IO del servidor MCP. La implementación es responsable de saltar
 * a su propio hilo de UI si hace falta y de bloquear hasta que el operador decida.
 */
interface ApprovalGate {

    suspend fun reviewCommands(
        prompt: String,
        vendor: Vendor,
        classifications: List<ClassifiedCommand>,
    ): ApprovalDecision
}