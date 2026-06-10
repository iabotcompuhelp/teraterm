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
 *
 * `contextPath` viene del cliente MCP, así que se confina a [allowedContextRoot]
 * (default `~/.opentermx/`): sin esto un cliente autenticado podría leer cualquier
 * YAML/JSON legible por el proceso vía `contextPath: "../../lo-que-sea.yaml"`.
 */
class StartOperationHandler(
    private val registry: OperationRegistry,
    private val allowedContextRoot: Path = Path.of(System.getProperty("user.home"), ".opentermx"),
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
                path != null && path.isNotBlank() -> OperationContextLoader.fromPath(resolveSafePath(path))
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

    /**
     * Normaliza [raw] y verifica que caiga bajo [allowedContextRoot]. Resuelve symlinks
     * (`toRealPath`) cuando el archivo existe, para que un link dentro del root no pueda
     * apuntar afuera. El mensaje de error no ecoa el path resuelto — sólo el root permitido —
     * para no convertir el rechazo en un oráculo de qué existe en el filesystem.
     */
    private fun resolveSafePath(raw: String): Path {
        val candidate = try {
            Path.of(raw).toAbsolutePath().normalize()
        } catch (e: java.nio.file.InvalidPathException) {
            throw McpToolException(INVALID_ARGUMENT, "contextPath no es un path válido")
        }
        val root = runCatching { allowedContextRoot.toRealPath() }
            .getOrDefault(allowedContextRoot.toAbsolutePath().normalize())
        val resolved = runCatching { candidate.toRealPath() }.getOrDefault(candidate)
        if (!resolved.startsWith(root)) {
            throw McpToolException(
                INVALID_ARGUMENT,
                "contextPath debe estar dentro de `$root`; usá contextInline/contextYaml para contexts fuera de ese directorio",
            )
        }
        return resolved
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
