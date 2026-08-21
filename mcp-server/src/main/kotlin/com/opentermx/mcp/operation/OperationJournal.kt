package com.opentermx.mcp.operation

import com.fasterxml.jackson.annotation.JsonInclude

/** Evento durable y ordenado de una operación. Los payloads deben llegar redactados. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class OperationJournalEntry(
    val sequence: Long,
    val timestampMillis: Long,
    val type: OperationEventType,
    val source: String,
    val correlationId: String,
    val toolName: String? = null,
    val status: String? = null,
    val payload: Map<String, Any?> = emptyMap(),
)

enum class OperationEventType {
    OPERATION_STARTED,
    OPERATION_RESUMED,
    TOOL_STARTED,
    TOOL_SUCCEEDED,
    TOOL_REJECTED,
    TOOL_UNKNOWN,
    OPERATOR_DECISION,
    OPERATION_ENDED,
}

data class OperatorDecision(
    val sequence: Long,
    val timestampMillis: Long,
    val correlationId: String,
    val toolName: String,
    val decision: String,
    val source: String,
    val rationale: String? = null,
)

/** Referencia verificable a evidencia; el handoff no transporta blobs. */
data class EvidenceReference(
    val id: String,
    val kind: String,
    val sha256: String? = null,
    val createdAtMillis: Long? = null,
    val deviceAlias: String? = null,
    val sessionId: String? = null,
    val label: String? = null,
)

/** Contrato portable que puede consumir cualquier proveedor LLM. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class OperationHandoff(
    val schemaVersion: String = SCHEMA_VERSION,
    val operationId: String,
    val generatedAtMillis: Long,
    val status: String,
    val objective: String,
    val context: OperationContext,
    val journal: List<OperationJournalEntry>,
    val decisions: List<OperatorDecision> = emptyList(),
    val evidence: List<EvidenceReference> = emptyList(),
    val factualSummary: String,
    val openRisks: List<String> = emptyList(),
    val nextSteps: List<String> = emptyList(),
    val sourceProvider: String? = null,
    val targetProvider: String? = null,
    val changeReason: String? = null,
    val budgetRemaining: Long? = null,
) {
    companion object {
        const val SCHEMA_VERSION = "1.0"
    }
}

/** Compactación estable: mismo journal produce el mismo resumen, sin pedirle nada a un LLM. */
object OperationHandoffBuilder {
    fun build(
        record: OperationRecord,
        journal: List<OperationJournalEntry>,
        generatedAtMillis: Long = System.currentTimeMillis(),
        sourceProvider: String? = null,
        targetProvider: String? = null,
        changeReason: String? = null,
        budgetRemaining: Long? = null,
        evidence: List<EvidenceReference> = emptyList(),
    ): OperationHandoff {
        val ordered = journal.sortedWith(compareBy(OperationJournalEntry::sequence, OperationJournalEntry::timestampMillis))
        val successes = ordered.count { it.type == OperationEventType.TOOL_SUCCEEDED }
        val rejections = ordered.count { it.type == OperationEventType.TOOL_REJECTED }
        val unknown = ordered.count { it.type == OperationEventType.TOOL_UNKNOWN }
        val decisions = ordered.filter { it.type == OperationEventType.OPERATOR_DECISION }.mapNotNull { entry ->
            val tool = entry.toolName ?: return@mapNotNull null
            OperatorDecision(entry.sequence, entry.timestampMillis, entry.correlationId, tool,
                entry.status ?: OperationEventType.TOOL_UNKNOWN.name, entry.source,
                entry.payload.values.firstOrNull() as? String)
        }
        val lastTools = ordered.asReversed().mapNotNull { it.toolName }.distinct().take(5).reversed()
        val summary = buildString {
            append("Operación activa: ").append(record.context.operation.description).append(". ")
            append(successes).append(" tools completadas; ").append(rejections).append(" rechazadas.")
            if (lastTools.isNotEmpty()) append(" Tools recientes: ").append(lastTools.joinToString(", ")).append('.')
        }
        val risks = ordered
            .filter { it.type in setOf(OperationEventType.TOOL_REJECTED, OperationEventType.TOOL_UNKNOWN) }
            .mapNotNull { it.payload["message"] as? String }
            .distinct()
            .takeLast(10)
        return OperationHandoff(
            operationId = record.operationId,
            generatedAtMillis = generatedAtMillis,
            status = if (unknown > 0) "UNKNOWN" else "ACTIVE",
            objective = record.context.operation.description,
            context = record.context,
            journal = ordered,
            decisions = decisions,
            evidence = evidence.sortedWith(compareBy(EvidenceReference::createdAtMillis, EvidenceReference::id)),
            factualSummary = summary,
            openRisks = risks,
            nextSteps = listOf("Confirmar objetivo, alcance y restricciones antes de continuar."),
            sourceProvider = sourceProvider,
            targetProvider = targetProvider,
            changeReason = changeReason,
            budgetRemaining = budgetRemaining,
        )
    }
}

/** Redacción defensiva y límite de tamaño antes de persistir datos de tools. */
object JournalPayloadSanitizer {
    private val secretKey = Regex("(?i).*(password|passwd|secret|token|api.?key|private.?key|credential).*" )

    fun sanitize(input: Map<String, Any?>): Map<String, Any?> = input.entries
        .sortedBy { it.key }
        .associate { (key, value) -> key to if (secretKey.matches(key)) "[REDACTED]" else sanitizeValue(value) }

    private fun sanitizeValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { (k, v) ->
            val key = k.toString()
            key to if (secretKey.matches(key)) "[REDACTED]" else sanitizeValue(v)
        }.toSortedMap()
        is Iterable<*> -> value.take(100).map(::sanitizeValue)
        is Array<*> -> value.take(100).map(::sanitizeValue)
        is String -> if (value.length <= 2_000) value else value.take(2_000) + "…[truncated]"
        is Number, is Boolean, null -> value
        else -> value.toString().take(2_000)
    }
}
