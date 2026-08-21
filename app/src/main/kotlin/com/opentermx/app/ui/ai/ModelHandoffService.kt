package com.opentermx.app.ui.ai

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.opentermx.app.settings.AiAssistantSettings
import com.opentermx.common.ai.ProviderKind
import com.opentermx.mcp.application.ToolExecutionResult

/** Orquesta preview/confirmación sin depender de JavaFX; la UI solo presenta el resultado. */
class ModelHandoffService(
    private val executeTool: suspend (String, Map<String, Any?>) -> ToolExecutionResult,
) {
    private val mapper = ObjectMapper().registerKotlinModule()

    data class Preview(
        val operationId: String,
        val targetProvider: ProviderKind,
        val targetModel: String,
        val rawJson: String,
        val contextPrompt: String,
        val journalEvents: Int,
        val evidenceCount: Int,
        val decisionCount: Int = 0,
        val unknownMutationCount: Int = 0,
    )

    suspend fun preview(
        settings: AiAssistantSettings,
        targetProvider: ProviderKind,
        targetModel: String,
        reason: String,
    ): Preview {
        require(targetProvider != settings.providerKind() || targetModel != settings.modelFor(targetProvider).orEmpty()) {
            "El proveedor y modelo de destino ya están activos"
        }
        val result = executeTool(
            "export_operation_handoff",
            linkedMapOf(
                "sourceProvider" to settings.providerKind().name,
                "targetProvider" to targetProvider.name,
                "changeReason" to reason.takeIf { it.isNotBlank() },
            ).filterValues { it != null },
        )
        val payload = when (result) {
            is ToolExecutionResult.Success -> result.payload
            is ToolExecutionResult.Rejected -> error(result.message)
        }
        val operationId = payload["operationId"]?.toString()
            ?: error("El handoff no contiene operationId")
        val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload)
        val journalCount = (payload["journal"] as? List<*>)?.size ?: 0
        val evidenceCount = (payload["evidence"] as? List<*>)?.size ?: 0
        val decisionCount = (payload["decisions"] as? List<*>)?.size ?: 0
        val unknownMutationCount = (payload["journal"] as? List<*>)
            .orEmpty().count { (it as? Map<*, *>).orEmpty()["status"] == "UNKNOWN" }
        val prompt = buildString {
            append("OPENTERMX HANDOFF VERIFICABLE\n")
            append("Antes de continuar confirma explícitamente objetivo, alcance y restricciones. ")
            append("No repitas tools mutativas y trata resultados UNKNOWN como pendientes de verificación.\n\n")
            append(json)
        }
        return Preview(operationId, targetProvider, targetModel, json, prompt, journalCount,
            evidenceCount, decisionCount, unknownMutationCount)
    }

    fun confirm(settings: AiAssistantSettings, preview: Preview): AiAssistantSettings {
        val selected = settings.selectedModels + (preview.targetProvider.name to preview.targetModel)
        val updated = settings.copy(
            provider = preview.targetProvider.name,
            selectedModels = selected,
            lastVerifiedAt = null,
            lastVerifiedProvider = null,
            lastVerifiedModel = null,
        )
        require(updated.isConfigured()) {
            "El proveedor ${preview.targetProvider.name} no tiene credenciales o endpoint configurado"
        }
        return updated
    }
}
