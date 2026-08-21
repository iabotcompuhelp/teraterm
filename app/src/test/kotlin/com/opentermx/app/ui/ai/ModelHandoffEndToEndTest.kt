package com.opentermx.app.ui.ai

import com.opentermx.app.settings.AiAssistantSettings
import com.opentermx.common.ai.ProviderKind
import com.opentermx.mcp.application.ToolExecutionContext
import com.opentermx.mcp.application.ToolExecutionResult
import com.opentermx.mcp.application.ToolExecutor
import com.opentermx.mcp.handlers.ExportOperationHandoffHandler
import com.opentermx.mcp.handlers.ResumeOperationHandler
import com.opentermx.mcp.handlers.ToolHandler
import com.opentermx.mcp.operation.FsOperationStore
import com.opentermx.mcp.operation.OperationContext
import com.opentermx.mcp.operation.OperationEventType
import com.opentermx.mcp.operation.OperationMeta
import com.opentermx.mcp.operation.OperationRegistry
import com.opentermx.mcp.operation.OperationScope
import com.opentermx.mcp.snapshots.InMemorySnapshotStore
import com.opentermx.mcp.snapshots.Snapshot
import com.opentermx.mcp.tools.ToolDefinitions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ModelHandoffEndToEndTest {
    @Test
    fun `fallo mutativo sobrevive reinicio y handoff no reintenta la tool`(@TempDir root: Path) = runBlocking {
        val sourceSession = "openai-source"
        val targetSession = "ollama-target"
        val operationId = "op-e2e-unknown"
        val firstRegistry = OperationRegistry(FsOperationStore(root))
        firstRegistry.start(
            sourceSession,
            OperationContext(
                operation = OperationMeta(id = operationId, description = "OBJ-NORTH"),
                scope = OperationScope(devices = listOf("SCOPE-CORE")),
            ),
            nowMillis = 10,
        )
        firstRegistry.appendEvent(
            operationId, OperationEventType.OPERATOR_DECISION, sourceSession, "corr-change",
            "propose_adapter_write", "APPROVED", mapOf("rationale" to "DEC-ALLOW"), nowMillis = 20,
        )
        val failingMutation = FailingMutationHandler()
        val executor = ToolExecutor(listOf(failingMutation), operationRegistry = firstRegistry)

        val result = executor.execute(
            "propose_adapter_write",
            mapOf("deviceHostname" to "SCOPE-CORE", "rationale" to "DEC-ALLOW"),
            ToolExecutionContext(sourceSession, correlationId = "corr-change"),
        )

        assertTrue(result is ToolExecutionResult.Rejected)
        assertEquals(1, failingMutation.invocations)
        assertEquals("UNKNOWN", firstRegistry.journal(operationId).last().status)

        // Simula reinicio: el nuevo registry reconstruye contexto y journal desde disco.
        val recoveredRegistry = OperationRegistry(FsOperationStore(root))
        recoveredRegistry.resume(sourceSession, operationId, nowMillis = 30)
        val snapshots = InMemorySnapshotStore().also { store ->
            store.save(
                Snapshot(
                    id = "EVID-9", operationId = operationId, sessionId = "terminal-1",
                    deviceAlias = "SCOPE-CORE", snapshotType = "running_config",
                    timestampMillis = 25, contentHash = "a".repeat(64),
                    content = "sensitive configuration", label = "pre-change",
                ),
            )
        }
        val exportHandler = ExportOperationHandoffHandler(recoveredRegistry, snapshots)
        val handoffService = ModelHandoffService { tool, args ->
            assertEquals("export_operation_handoff", tool)
            ToolExecutionResult.Success(exportHandler.invoke(args, sourceSession))
        }

        val preview = handoffService.preview(
            AiAssistantSettings(provider = ProviderKind.OPENAI.name),
            ProviderKind.OLLAMA,
            "test-target-model",
            "recover after uncertain mutation",
        )

        assertEquals(operationId, preview.operationId)
        assertEquals(1, preview.decisionCount)
        assertEquals(1, preview.evidenceCount)
        assertEquals(1, preview.unknownMutationCount)
        assertTrue(preview.rawJson.contains("OBJ-NORTH"))
        assertTrue(preview.rawJson.contains("SCOPE-CORE"))
        assertTrue(preview.rawJson.contains("DEC-ALLOW"))
        assertTrue(preview.rawJson.contains("EVID-9"))
        assertTrue(preview.contextPrompt.contains("No repitas tools mutativas"))
        assertTrue(preview.contextPrompt.contains("UNKNOWN"))
        assertTrue(!preview.rawJson.contains("sensitive configuration"))

        val resumed = ResumeOperationHandler(recoveredRegistry).invoke(
            mapOf("operationId" to operationId), targetSession,
        )
        assertEquals(true, resumed["resumed"])
        assertEquals(true, resumed["confirmationRequired"])
        assertEquals(operationId, recoveredRegistry.forSessionKey(targetSession)?.operationId)
        assertEquals(null, recoveredRegistry.forSessionKey(sourceSession))
        assertEquals(1, failingMutation.invocations, "el resume/handoff no debe reejecutar la mutación")
    }

    private class FailingMutationHandler : ToolHandler {
        var invocations: Int = 0
            private set

        override val definition = ToolDefinitions.PROPOSE_ADAPTER_WRITE

        override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
            invocations++
            throw IllegalStateException("connection lost after dispatch")
        }
    }
}
