package com.opentermx.mcp.handlers

import com.opentermx.mcp.handlers.McpToolException.ErrorCode.INVALID_ARGUMENT
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.NOT_FOUND
import com.opentermx.mcp.operation.OperationContext
import com.opentermx.mcp.operation.OperationContextException
import com.opentermx.mcp.operation.OperationContextLoader
import com.opentermx.mcp.operation.OperationRegistry
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Path

/**
 * Handler de `start_operation`. Acepta el context en uno de tres formatos mutuamente
 * exclusivos (el JSON Schema lo enforce con `oneOf`): `contextPath`, `contextInline`, `contextYaml`.
 * Valida → registra en [OperationRegistry] → devuelve `operationId + startedAtMillis`.
 */
class StartOperationHandler(
    private val registry: OperationRegistry,
) : OperationAwareToolHandler {

    override val definition: ToolDef = ToolDefinitions.START_OPERATION

    override suspend fun invoke(args: Map<String, Any?>, sessionKey: String): Map<String, Any?> {
        val context = parseContext(args)
        val record = try {
            registry.start(sessionKey, context)
        } catch (e: OperationContextException) {
            throw McpToolException(INVALID_ARGUMENT, e.message ?: "no se pudo iniciar la operación")
        }
        return linkedMapOf(
            "operationId" to record.operationId,
            "startedAtMillis" to record.startedAtMillis,
            "description" to record.context.operation.description,
        )
    }

    private fun parseContext(args: Map<String, Any?>): OperationContext {
        val path = args["contextPath"] as? String
        val inline = args["contextInline"]
        val yaml = args["contextYaml"] as? String
        val provided = listOfNotNull(
            path?.takeIf { it.isNotBlank() },
            inline,
            yaml?.takeIf { it.isNotBlank() },
        )
        if (provided.size != 1) {
            throw McpToolException(
                INVALID_ARGUMENT,
                "Debe pasarse exactamente uno de: contextPath, contextInline, contextYaml",
            )
        }
        return try {
            when {
                path != null && path.isNotBlank() -> OperationContextLoader.fromPath(Path.of(path))
                yaml != null && yaml.isNotBlank() -> OperationContextLoader.fromYamlString(yaml)
                inline is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    OperationContextLoader.fromInline(inline as Map<String, Any?>)
                }
                else -> throw McpToolException(INVALID_ARGUMENT, "Formato de context inválido")
            }
        } catch (e: OperationContextException) {
            throw McpToolException(INVALID_ARGUMENT, e.message ?: "context inválido")
        }
    }
}

class EndOperationHandler(
    private val registry: OperationRegistry,
) : OperationAwareToolHandler {

    override val definition: ToolDef = ToolDefinitions.END_OPERATION

    override suspend fun invoke(args: Map<String, Any?>, sessionKey: String): Map<String, Any?> {
        val operationId = Args.requireString(args, "operationId")
        return try {
            registry.end(sessionKey, operationId)
        } catch (e: OperationContextException) {
            throw McpToolException(NOT_FOUND, e.message ?: "no se pudo cerrar la operación")
        }
    }
}

class CurrentOperationHandler(
    private val registry: OperationRegistry,
) : OperationAwareToolHandler {

    override val definition: ToolDef = ToolDefinitions.CURRENT_OPERATION

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    override suspend fun invoke(args: Map<String, Any?>, sessionKey: String): Map<String, Any?> {
        val active = registry.forSessionKey(sessionKey)
        return if (active == null) {
            linkedMapOf("operationId" to null, "context" to null)
        } else {
            // Devolvemos el context como `Map` para que el cliente lo vea como JSON object.
            val asMap: Map<String, Any?> = mapper.convertValue(active.context, Map::class.java)
                .mapKeys { it.key.toString() }
            linkedMapOf(
                "operationId" to active.operationId,
                "context" to asMap,
            )
        }
    }
}
