package com.opentermx.mcp.operation

/**
 * Produce el bloque compacto que [com.opentermx.mcp.protocol.McpDispatcher] prefija al
 * texto del primer `content[0].text` de cada `tools/call` exitoso mientras una operation
 * esté activa en la sessionKey del cliente.
 *
 * Diseño:
 *  - Texto plano (no JSON) — el cliente que no entiende el bloque lo ve como texto
 *    informativo y sigue funcionando.
 *  - Compacto: solo lo necesario para que el LLM lo lea por turno sin gastar tokens
 *    extra en metadata redundante.
 *  - Separador `---` antes del payload original para que el cliente pueda partir si
 *    quiere extraerlo.
 */
object OperationContextInjector {

    fun renderBlock(record: OperationRecord, criteriaStatus: String = "pending"): String {
        val ctx = record.context
        val sb = StringBuilder(256)
        sb.append("[OPERATION CONTEXT ").append(record.operationId).append("]\n")
        sb.append("description: ").append(ctx.operation.description).append('\n')
        if (ctx.scope.devices.isNotEmpty()) {
            sb.append("scope.devices: ").append(ctx.scope.devices.joinToString(", ")).append('\n')
        }
        if (ctx.scope.forbiddenCommands.isNotEmpty()) {
            sb.append("forbidden_commands: ")
                .append(ctx.scope.forbiddenCommands.joinToString(", "))
                .append('\n')
        }
        if (ctx.scope.allowedCommandsPrefix.isNotEmpty()) {
            sb.append("allowed_commands_prefix: ")
                .append(ctx.scope.allowedCommandsPrefix.joinToString(", "))
                .append('\n')
        }
        if (ctx.successCriteria.isNotEmpty()) {
            sb.append("success_criteria: ")
                .append(ctx.successCriteria.size)
                .append(' ')
                .append(criteriaStatus)
                .append('\n')
        }
        val constraintBits = mutableListOf<String>()
        if (ctx.constraints.requireComplianceApproval) constraintBits += "compliance_approval"
        if (ctx.constraints.requireSnapshot) constraintBits += "snapshot"
        if (constraintBits.isNotEmpty()) {
            sb.append("constraints: require(").append(constraintBits.joinToString(",")).append(")\n")
        }
        sb.append("---\n")
        return sb.toString()
    }

    /**
     * Prefija el bloque al `content[0].text` de una respuesta exitosa de `tools/call`,
     * dejando el resto del payload (incluido `structuredContent`) intacto.
     */
    fun injectIntoToolResult(
        toolResult: Map<String, Any?>,
        record: OperationRecord,
    ): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val content = toolResult["content"] as? List<Map<String, Any?>> ?: return toolResult
        if (content.isEmpty()) return toolResult
        val first = content[0]
        if (first["type"] != "text") return toolResult
        val originalText = first["text"] as? String ?: return toolResult
        val block = renderBlock(record)
        val mutated = LinkedHashMap(first)
        mutated["text"] = block + originalText
        val newContent = listOf<Map<String, Any?>>(mutated) + content.drop(1)
        val out = LinkedHashMap(toolResult)
        out["content"] = newContent
        return out
    }
}
