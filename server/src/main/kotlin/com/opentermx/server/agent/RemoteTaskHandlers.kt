package com.opentermx.server.agent

import com.opentermx.agent.RemoteCommandTask
import com.opentermx.mcp.handlers.McpToolException
import com.opentermx.mcp.handlers.OperationAwareToolHandler
import com.opentermx.mcp.operation.OperationEventType
import com.opentermx.mcp.operation.OperationRegistry
import com.opentermx.mcp.operation.CommandValidation
import com.opentermx.mcp.operation.validateCommand
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions
import java.util.UUID

class ProposeRemoteCommandsHandler(
    private val operations: OperationRegistry,
    private val agents: AgentRegistry,
    private val tasks: RemoteTaskStore,
) : OperationAwareToolHandler {
    override val definition: ToolDef = ToolDefinitions.PROPOSE_REMOTE_COMMANDS

    override suspend fun invoke(args: Map<String, Any?>, sessionKey: String): Map<String, Any?> {
        val operation = operations.forSessionKey(sessionKey)
            ?: throw McpToolException(McpToolException.ErrorCode.INVALID_ARGUMENT, "Se requiere una operación activa")
        val federatedId = args["sessionId"] as? String
            ?: throw McpToolException(McpToolException.ErrorCode.INVALID_ARGUMENT, "sessionId requerido")
        val remote = agents.find(federatedId)
            ?: throw McpToolException(McpToolException.ErrorCode.NOT_FOUND, "Sesión remota no encontrada")
        @Suppress("UNCHECKED_CAST")
        val commands = (args["commands"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        val rationale = (args["rationale"] as? String).orEmpty()
        if (operation.context.scope.devices.isNotEmpty()) {
            val identities = setOf(federatedId, remote.agentId, remote.snapshot.host).filterNotNull()
            if (operation.context.scope.devices.none { allowed -> identities.any { it.equals(allowed, ignoreCase = true) } }) {
                throw McpToolException(McpToolException.ErrorCode.INVALID_ARGUMENT, "Sesión fuera del scope.devices de la operación")
            }
        }
        commands.forEach { command ->
            val validation = operation.context.scope.validateCommand(command)
            if (validation is CommandValidation.Rejected) {
                throw McpToolException(McpToolException.ErrorCode.INVALID_ARGUMENT, validation.reason)
            }
        }
        val expiresSeconds = ((args["expiresInSeconds"] as? Number)?.toLong() ?: 300).coerceIn(30, 900)
        val now = System.currentTimeMillis()
        val task = tasks.enqueue(
            RemoteCommandTask(
                taskId = "task-${UUID.randomUUID().toString().take(12)}",
                operationId = operation.operationId,
                agentId = remote.agentId,
                sessionId = remote.snapshot.sessionId,
                commands = commands,
                rationale = rationale,
                createdAtMillis = now,
                expiresAtMillis = now + expiresSeconds * 1_000,
            ),
        )
        operations.appendEvent(
            operation.operationId, OperationEventType.TOOL_STARTED, sessionKey, task.taskId,
            toolName = definition.name, status = task.status.name,
            payload = mapOf("taskId" to task.taskId, "sessionId" to federatedId, "commandCount" to commands.size),
        )
        return mapOf("taskId" to task.taskId, "status" to task.status.name, "operationId" to operation.operationId)
    }
}

class GetRemoteTaskHandler(
    private val operations: OperationRegistry,
    private val tasks: RemoteTaskStore,
) : OperationAwareToolHandler {
    override val definition: ToolDef = ToolDefinitions.GET_REMOTE_TASK

    override suspend fun invoke(args: Map<String, Any?>, sessionKey: String): Map<String, Any?> {
        val task = ownedTask(args, sessionKey, operations, tasks)
        val result = tasks.result(task.taskId)
        return linkedMapOf(
            "taskId" to task.taskId,
            "status" to task.status.name,
            "completedAtMillis" to result?.completedAtMillis,
            "output" to result?.output,
            "error" to result?.error,
        )
    }
}

class CancelRemoteTaskHandler(
    private val operations: OperationRegistry,
    private val tasks: RemoteTaskStore,
) : OperationAwareToolHandler {
    override val definition: ToolDef = ToolDefinitions.CANCEL_REMOTE_TASK

    override suspend fun invoke(args: Map<String, Any?>, sessionKey: String): Map<String, Any?> {
        val task = ownedTask(args, sessionKey, operations, tasks)
        val cancelled = try { tasks.cancel(task.taskId) } catch (e: IllegalArgumentException) {
            throw McpToolException(McpToolException.ErrorCode.INVALID_ARGUMENT, e.message ?: "No se pudo cancelar")
        }
        operations.appendEvent(
            task.operationId!!, OperationEventType.TOOL_REJECTED, sessionKey, task.taskId,
            toolName = definition.name, status = cancelled.status.name,
            payload = mapOf("taskId" to task.taskId, "message" to "Tarea cancelada"),
        )
        return mapOf("taskId" to task.taskId, "status" to cancelled.status.name)
    }
}

private fun ownedTask(
    args: Map<String, Any?>,
    sessionKey: String,
    operations: OperationRegistry,
    tasks: RemoteTaskStore,
): RemoteCommandTask {
    val operation = operations.forSessionKey(sessionKey)
        ?: throw McpToolException(McpToolException.ErrorCode.INVALID_ARGUMENT, "Se requiere una operación activa")
    val id = args["taskId"] as? String
        ?: throw McpToolException(McpToolException.ErrorCode.INVALID_ARGUMENT, "taskId requerido")
    val task = tasks.task(id)
        ?: throw McpToolException(McpToolException.ErrorCode.NOT_FOUND, "Tarea no encontrada")
    if (task.operationId != operation.operationId) {
        throw McpToolException(McpToolException.ErrorCode.NOT_FOUND, "Tarea no encontrada en la operación activa")
    }
    return task
}
