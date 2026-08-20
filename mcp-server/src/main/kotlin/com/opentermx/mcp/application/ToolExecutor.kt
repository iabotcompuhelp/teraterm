package com.opentermx.mcp.application

import com.opentermx.mcp.handlers.McpToolException
import com.opentermx.mcp.handlers.OperationAwareToolHandler
import com.opentermx.mcp.handlers.ToolHandler
import com.opentermx.mcp.operation.CommandValidation
import com.opentermx.mcp.operation.OperationEventType
import com.opentermx.mcp.operation.OperationRecord
import com.opentermx.mcp.operation.OperationRegistry
import com.opentermx.mcp.operation.validateCommand
import com.opentermx.mcp.security.GlobMatcher
import com.opentermx.mcp.security.Role
import com.opentermx.mcp.security.RoleAccessControl
import com.opentermx.mcp.tools.ToolDef
import org.slf4j.LoggerFactory
import java.util.UUID

/** Transport-independent application entry point for all OpenTermX tools. */
class ToolExecutor(
    handlers: List<ToolHandler>,
    private val readOnly: Boolean = false,
    private val allowedSessionGlob: String? = null,
    private val operationRegistry: OperationRegistry? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val handlersByName = handlers.associateBy { it.definition.name }

    val definitions: List<ToolDef> get() = handlersByName.values.map { it.definition }
    val toolNames: Set<String> get() = handlersByName.keys

    suspend fun execute(
        toolName: String,
        arguments: Map<String, Any?>,
        context: ToolExecutionContext,
    ): ToolExecutionResult {
        val handler = handlersByName[toolName]
            ?: return ToolExecutionResult.Rejected(ToolRejection.UNKNOWN_TOOL, "Tool desconocida: `$toolName`")
        if (!RoleAccessControl.allows(context.role, toolName)) {
            return ToolExecutionResult.Rejected(
                ToolRejection.FORBIDDEN,
                "Tool `$toolName` no permitida para rol `${context.role.name}`",
            )
        }
        if ((readOnly || context.forceReadOnly) && handler.definition.mutating) {
            return ToolExecutionResult.Rejected(
                ToolRejection.FORBIDDEN,
                "Tool `$toolName` deshabilitada: el ejecutor está en modo read-only",
            )
        }
        val sessionId = arguments["sessionId"] as? String
        if (sessionId != null && !GlobMatcher.matches(allowedSessionGlob, sessionId)) {
            return ToolExecutionResult.Rejected(
                ToolRejection.INVALID_ARGUMENT,
                "Sesión `$sessionId` fuera del scope permitido (glob=`$allowedSessionGlob`)",
            )
        }

        val activeOperation = operationRegistry?.forSessionKey(context.sessionKey)
        val correlationId = context.correlationId ?: UUID.randomUUID().toString()
        val violation = activeOperation?.let { validateOperationScope(it, arguments) }
        if (violation != null) {
            log.info("Operation `{}` bloqueó comandos en `{}`", activeOperation.operationId, toolName)
            operationRegistry.appendEvent(
                activeOperation.operationId, OperationEventType.TOOL_REJECTED,
                context.sessionKey, correlationId, toolName, "POLICY",
                mapOf("arguments" to arguments, "message" to violation),
            )
            return ToolExecutionResult.Rejected(ToolRejection.POLICY, violation)
        }

        activeOperation?.let {
            operationRegistry?.appendEvent(
                it.operationId, OperationEventType.TOOL_STARTED,
                context.sessionKey, correlationId, toolName, "IN_PROGRESS",
                mapOf("arguments" to arguments),
            )
        }
        return try {
            val payload = if (handler is OperationAwareToolHandler) {
                handler.invoke(arguments, context.sessionKey)
            } else {
                handler.invoke(arguments)
            }
            activeOperation?.let {
                operationRegistry?.appendEvent(
                    it.operationId, OperationEventType.TOOL_SUCCEEDED,
                    context.sessionKey, correlationId, toolName, "SUCCEEDED",
                    mapOf("result" to payload),
                )
            }
            ToolExecutionResult.Success(payload, activeOperation, correlationId)
        } catch (e: McpToolException) {
            log.debug("Tool `{}` rechazó input: {}", toolName, e.message)
            activeOperation?.let {
                operationRegistry?.appendEvent(
                    it.operationId, OperationEventType.TOOL_REJECTED,
                    context.sessionKey, correlationId, toolName, "REJECTED",
                    mapOf("message" to e.message),
                )
            }
            ToolExecutionResult.Rejected(ToolRejection.HANDLER, e.message ?: "Error de invocación", e)
        } catch (e: Throwable) {
            log.warn("Tool `{}` lanzó excepción inesperada", toolName, e)
            activeOperation?.let {
                operationRegistry?.appendEvent(
                    it.operationId, OperationEventType.TOOL_REJECTED,
                    context.sessionKey, correlationId, toolName, "FAILED",
                    mapOf("message" to (e.message ?: e.javaClass.simpleName)),
                )
            }
            ToolExecutionResult.Rejected(ToolRejection.INTERNAL, e.message ?: e.javaClass.simpleName, e)
        }
    }

    private fun validateOperationScope(
        operation: OperationRecord,
        arguments: Map<String, Any?>,
    ): String? {
        val commands = (arguments["commands"] as? List<*>).orEmpty().mapNotNull { it as? String } +
            listOfNotNull(arguments["command"] as? String)
        val violations = commands.mapNotNull { command ->
            (operation.context.scope.validateCommand(command) as? CommandValidation.Rejected)?.reason
        }
        return violations.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}

data class ToolExecutionContext(
    val sessionKey: String,
    val role: Role = Role.OPERATOR,
    val forceReadOnly: Boolean = false,
    val correlationId: String? = null,
) {
    companion object {
        fun internalChat() = ToolExecutionContext("internal-chat")
        fun test() = ToolExecutionContext("test")
    }
}

sealed interface ToolExecutionResult {
    data class Success(
        val payload: Map<String, Any?>,
        val activeOperation: OperationRecord? = null,
        val correlationId: String? = null,
    ) : ToolExecutionResult

    data class Rejected(
        val reason: ToolRejection,
        val message: String,
        val cause: Throwable? = null,
    ) : ToolExecutionResult
}

enum class ToolRejection { UNKNOWN_TOOL, FORBIDDEN, INVALID_ARGUMENT, POLICY, HANDLER, INTERNAL }
