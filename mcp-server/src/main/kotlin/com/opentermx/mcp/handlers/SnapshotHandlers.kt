package com.opentermx.mcp.handlers

import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.INVALID_ARGUMENT
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.NOT_FOUND
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.UNAVAILABLE
import com.opentermx.mcp.operation.OperationRegistry
import com.opentermx.mcp.snapshots.RollbackProposer
import com.opentermx.mcp.snapshots.Snapshot
import com.opentermx.mcp.snapshots.SnapshotDiffer
import com.opentermx.mcp.snapshots.SnapshotStore
import com.opentermx.mcp.snapshots.SuccessCriteriaEvaluator
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions

/**
 * Phase 3 Fase 4 — handlers de snapshots. Todos son read-only (mutating=false): no
 * tocan el device, solo leen el buffer del SessionRegistry, calculan o persisten
 * resultados localmente.
 *
 * Los 4 handlers son OperationAware: necesitan saber la op activa para indexar el
 * snapshot bajo `~/.opentermx/operations/<op-id>/snapshots/` y para que
 * `snapshot_compare_to_criteria` pueda leer los success_criteria.
 */
class SnapshotCreateHandler(
    private val store: SnapshotStore,
    private val operationRegistry: OperationRegistry,
) : OperationAwareToolHandler {

    override val definition: ToolDef = ToolDefinitions.SNAPSHOT_CREATE

    override suspend fun invoke(args: Map<String, Any?>, sessionKey: String): Map<String, Any?> {
        val sessionIdRaw = Args.requireString(args, "sessionId")
        val snapshotType = Args.requireString(args, "snapshotType")
        val lastLines = (args["lastLines"] as? Number)?.toInt() ?: ToolDefinitions.DEFAULT_SNAPSHOT_LINES
        if (lastLines !in 1..ToolDefinitions.MAX_SNAPSHOT_LINES) {
            throw McpToolException(INVALID_ARGUMENT,
                "lastLines fuera de rango [1, ${ToolDefinitions.MAX_SNAPSHOT_LINES}]: $lastLines")
        }
        val deviceAlias = Args.optionalString(args, "deviceAlias")
        val label = Args.optionalString(args, "label")

        val sessionId = SessionId(sessionIdRaw)
        SessionRegistry.metadataOf(sessionId)
            ?: throw McpToolException(NOT_FOUND, "Sesión `$sessionIdRaw` no encontrada")

        val lines = SessionRegistry.lastLinesOf(sessionId, lastLines)
        if (lines.isEmpty()) {
            throw McpToolException(UNAVAILABLE, "Buffer vacío para sesión `$sessionIdRaw`")
        }
        val content = lines.joinToString("\n")
        val op = operationRegistry.forSessionKey(sessionKey)
        val snapshot = Snapshot(
            id = Snapshot.newId(),
            operationId = op?.operationId,
            sessionId = sessionIdRaw,
            deviceAlias = deviceAlias,
            snapshotType = snapshotType,
            timestampMillis = System.currentTimeMillis(),
            contentHash = Snapshot.hashOf(content),
            content = content,
            label = label,
        )
        store.save(snapshot)
        return linkedMapOf(
            "snapshotId" to snapshot.id,
            "contentHash" to snapshot.contentHash,
            "lineCount" to lines.size,
            "operationId" to snapshot.operationId,
        )
    }
}

class SnapshotDiffHandler(
    private val store: SnapshotStore,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.SNAPSHOT_DIFF

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val before = loadOrThrow(store, Args.requireString(args, "snapshotIdBefore"))
        val after = loadOrThrow(store, Args.requireString(args, "snapshotIdAfter"))
        val deviceType = Args.optionalString(args, "deviceType")
        val diff = SnapshotDiffer.diff(before, after, deviceType)
        return linkedMapOf(
            "summary" to diff.summary,
            "addedLines" to diff.addedLines,
            "removedLines" to diff.removedLines,
            "identicalLineCount" to diff.identicalLineCount,
            "sections" to diff.sections.map { section ->
                linkedMapOf(
                    "header" to section.header,
                    "change" to section.change.name,
                    "addedLines" to section.addedLines,
                    "removedLines" to section.removedLines,
                )
            },
        )
    }
}

class SnapshotCompareToCriteriaHandler(
    private val store: SnapshotStore,
    private val operationRegistry: OperationRegistry,
) : OperationAwareToolHandler {

    override val definition: ToolDef = ToolDefinitions.SNAPSHOT_COMPARE_TO_CRITERIA

    override suspend fun invoke(args: Map<String, Any?>, sessionKey: String): Map<String, Any?> {
        val after = loadOrThrow(store, Args.requireString(args, "snapshotIdAfter"))
        val opIdOverride = Args.optionalString(args, "operationId")
        val operationId = opIdOverride
            ?: operationRegistry.forSessionKey(sessionKey)?.operationId
            ?: after.operationId
            ?: throw McpToolException(INVALID_ARGUMENT,
                "Sin op activa ni operationId override — no hay criteria para evaluar")
        val record = operationRegistry.forOperationId(operationId)
            ?: throw McpToolException(NOT_FOUND, "Operation `$operationId` no encontrada")
        val criteria = record.context.successCriteria
        val eval = SuccessCriteriaEvaluator.evaluate(after, criteria)
        return linkedMapOf(
            "overall" to eval.overall.name,
            "summary" to eval.summary,
            "results" to eval.results.map { r ->
                linkedMapOf(
                    "type" to r.type,
                    "status" to r.status.name,
                    "message" to r.message,
                )
            },
        )
    }
}

class RollbackProposeHandler(
    private val store: SnapshotStore,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.ROLLBACK_PROPOSE

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val before = loadOrThrow(store, Args.requireString(args, "snapshotIdBefore"))
        val after = loadOrThrow(store, Args.requireString(args, "snapshotIdAfter"))
        val deviceType = Args.optionalString(args, "deviceType")
        val plan = RollbackProposer.propose(before, after, deviceType)
        return linkedMapOf(
            "supported" to plan.supported,
            "commands" to plan.commands,
            "notes" to plan.notes,
            "deviceType" to plan.deviceType,
        )
    }
}

private fun loadOrThrow(store: SnapshotStore, id: String): Snapshot {
    return store.load(id) ?: throw McpToolException(NOT_FOUND, "Snapshot `$id` no existe")
}
