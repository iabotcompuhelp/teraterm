package com.opentermx.mcp.operation

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

/**
 * Registro de operaciones activas durante el runtime del MCP server. Mantiene dos
 * invariantes:
 *
 *  1. **Una operation a la vez por sessionKey MCP.** Si una `sessionKey` ya tiene una
 *     op activa, `start` falla con error claro (evita que un cliente confundido pierda
 *     el handle de la primera y arranque otra encima).
 *
 *  2. **operationId único globalmente.** Si dos sessionKeys distintas piden el mismo id,
 *     la segunda falla. Pensado para coordinar dos LLM (operator + compliance) que
 *     comparten una op pero corren en sessions HTTP distintas (cuando llegue Fase 3 con
 *     roles, esa coordinación va a ser explícita).
 *
 * La persistencia se delega a [OperationStore]. Al instanciarse, hace recovery leyendo
 * todas las ops abiertas del store. Cada `sessionKey` que vuelva a aparecer post-restart
 * "rebindea" su op activa la primera vez que llama `start_operation`... mejor: dejamos
 * todas las ops recuperadas accesibles vía `forOperationId(id)` y exigimos que el
 * cliente las refresque con un `start_operation` nuevo si quiere reactivar el binding
 * por sessionKey. Esto evita rebindings implícitos peligrosos.
 */
class OperationRegistry(
    private val store: OperationStore,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val bySessionKey = ConcurrentHashMap<String, OperationRecord>()
    private val byOperationId = ConcurrentHashMap<String, OperationRecord>()
    private val journalSequences = ConcurrentHashMap<String, AtomicLong>()

    init {
        store.loadAllOpen().forEach { rec ->
            byOperationId[rec.operationId] = rec
            val lastSequence = store.loadJournal(rec.operationId).maxOfOrNull { it.sequence } ?: 0L
            journalSequences[rec.operationId] = AtomicLong(lastSequence)
            log.info("Operation recuperada desde store: ${rec.operationId}")
        }
    }

    /**
     * Devuelve el record creado. Lanza [OperationContextException] si:
     *  - la sessionKey ya tiene una op activa,
     *  - el operationId resultante ya existe en otra session.
     */
    fun start(
        sessionKey: String,
        context: OperationContext,
        nowMillis: Long = System.currentTimeMillis(),
        idGenerator: () -> String = { generateId(nowMillis) },
    ): OperationRecord {
        if (bySessionKey.containsKey(sessionKey)) {
            val existing = bySessionKey.getValue(sessionKey)
            throw OperationContextException(
                "Ya hay una operación activa para esta sesión MCP: ${existing.operationId}. " +
                    "Cerrala con `end_operation` antes de iniciar otra."
            )
        }
        val finalId = context.operation.id?.takeIf { it.isNotBlank() } ?: idGenerator()
        if (byOperationId.containsKey(finalId)) {
            throw OperationContextException("Ya existe una operación con id `$finalId`")
        }
        val normalized = if (context.operation.id == finalId) {
            context
        } else {
            context.copy(operation = context.operation.copy(id = finalId))
        }
        val record = OperationRecord(
            operationId = finalId,
            context = normalized,
            startedAtMillis = nowMillis,
            initiatedBySessionKey = sessionKey,
        )
        bySessionKey[sessionKey] = record
        byOperationId[finalId] = record
        store.save(record)
        appendEvent(
            operationId = finalId,
            type = OperationEventType.OPERATION_STARTED,
            source = "system",
            correlationId = "operation:$finalId",
            payload = mapOf("description" to normalized.operation.description),
            nowMillis = nowMillis,
        )
        log.info("Operation started: $finalId (sessionKey=$sessionKey)")
        return record
    }

    /**
     * Cierra la operation y devuelve un summary mínimo. Si `operationId` no existe o
     * la sessionKey no es la dueña, lanza para que el handler la convierta en error MCP.
     */
    fun end(
        sessionKey: String,
        operationId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): Map<String, Any?> {
        val record = byOperationId[operationId]
            ?: throw OperationContextException("Operation `$operationId` no encontrada")
        if (record.initiatedBySessionKey != sessionKey) {
            throw OperationContextException(
                "Operation `$operationId` no pertenece a esta sesión MCP"
            )
        }
        bySessionKey.remove(sessionKey)
        byOperationId.remove(operationId)
        val durationMs = nowMillis - record.startedAtMillis
        val summary = linkedMapOf<String, Any?>(
            "operationId" to operationId,
            "durationMillis" to durationMs,
            "description" to record.context.operation.description,
        )
        appendEvent(
            operationId = operationId,
            type = OperationEventType.OPERATION_ENDED,
            source = "system",
            correlationId = "operation:$operationId",
            payload = summary,
            nowMillis = nowMillis,
        )
        store.markClosed(operationId, nowMillis, summary)
        log.info("Operation ended: $operationId (durationMs=$durationMs)")
        return summary
    }

    fun forSessionKey(sessionKey: String): OperationRecord? = bySessionKey[sessionKey]

    fun forOperationId(operationId: String): OperationRecord? = byOperationId[operationId]

    /** Reasocia una operación recuperada o compartida a una nueva sesión/LLM. */
    @Synchronized
    fun resume(sessionKey: String, operationId: String, nowMillis: Long = System.currentTimeMillis()): OperationRecord {
        if (bySessionKey.containsKey(sessionKey)) {
            throw OperationContextException("Ya hay una operación activa para esta sesión MCP")
        }
        val existing = byOperationId[operationId]
            ?: throw OperationContextException("Operation `$operationId` no encontrada o cerrada")
        // Transferencia explícita de ownership: una sola sesión puede continuar/escribir.
        bySessionKey.entries.removeIf { it.value.operationId == operationId }
        val record = existing.copy(initiatedBySessionKey = sessionKey)
        byOperationId[operationId] = record
        bySessionKey[sessionKey] = record
        store.save(record)
        appendEvent(
            operationId = operationId,
            type = OperationEventType.OPERATION_RESUMED,
            source = sessionKey,
            correlationId = "resume:$operationId:$nowMillis",
            nowMillis = nowMillis,
        )
        return record
    }

    fun appendEvent(
        operationId: String,
        type: OperationEventType,
        source: String,
        correlationId: String,
        toolName: String? = null,
        status: String? = null,
        payload: Map<String, Any?> = emptyMap(),
        nowMillis: Long = System.currentTimeMillis(),
    ): OperationJournalEntry {
        if (!byOperationId.containsKey(operationId) && type != OperationEventType.OPERATION_ENDED) {
            throw OperationContextException("Operation `$operationId` no encontrada")
        }
        val sequence = journalSequences.computeIfAbsent(operationId) { AtomicLong(0) }.incrementAndGet()
        val entry = OperationJournalEntry(
            sequence = sequence,
            timestampMillis = nowMillis,
            type = type,
            source = source,
            correlationId = correlationId,
            toolName = toolName,
            status = status,
            payload = JournalPayloadSanitizer.sanitize(payload),
        )
        store.appendJournal(operationId, entry)
        return entry
    }

    fun journal(operationId: String): List<OperationJournalEntry> {
        if (!byOperationId.containsKey(operationId)) {
            throw OperationContextException("Operation `$operationId` no encontrada o cerrada")
        }
        return store.loadJournal(operationId).sortedBy { it.sequence }
    }

    fun exportHandoff(
        operationId: String,
        sourceProvider: String? = null,
        targetProvider: String? = null,
        changeReason: String? = null,
        budgetRemaining: Long? = null,
        evidence: List<EvidenceReference> = emptyList(),
        nowMillis: Long = System.currentTimeMillis(),
    ): OperationHandoff {
        val record = byOperationId[operationId]
            ?: throw OperationContextException("Operation `$operationId` no encontrada o cerrada")
        val handoff = OperationHandoffBuilder.build(
            record = record,
            journal = store.loadJournal(operationId),
            generatedAtMillis = nowMillis,
            sourceProvider = sourceProvider,
            targetProvider = targetProvider,
            changeReason = changeReason,
            budgetRemaining = budgetRemaining,
            evidence = evidence,
        )
        store.saveHandoff(handoff)
        return handoff
    }

    /**
     * Phase 3 Fase 3 helper: ops activas que declaran `require_compliance_approval=true`.
     * Lo usa `ProposeCommandsHandler` para saber si exigir un approval token o no.
     */
    fun activeOperationsRequiringComplianceApproval(): List<OperationRecord> =
        byOperationId.values.filter { it.context.constraints.requireComplianceApproval }

    /**
     * Phase 3 Fase 4 helper: ops activas que exigen un snapshot previo antes de mutar.
     */
    fun activeOperationsRequiringSnapshot(): List<OperationRecord> =
        byOperationId.values.filter { it.context.constraints.requireSnapshot }

    /** Solo para tests: limpia todo. NO toca el store. */
    internal fun clearForTests() {
        bySessionKey.clear()
        byOperationId.clear()
        journalSequences.clear()
    }

    companion object {
        /** Genera ids como `op-1715961234567-a1b2c3` — únicos y ordenables por tiempo. */
        fun generateId(nowMillis: Long): String {
            val short = UUID.randomUUID().toString().take(6)
            return "op-$nowMillis-$short"
        }
    }
}
