package com.opentermx.mcp.protocol

import com.opentermx.mcp.handlers.McpToolException
import com.opentermx.mcp.handlers.ToolHandler
import com.opentermx.mcp.tools.ToolDef
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Implementa el subset del protocolo MCP (JSON-RPC 2.0) necesario para clientes tipo
 * Claude Desktop, Cursor o Claude Code:
 *
 *  - `initialize` — devuelve capabilities + serverInfo. El cliente lo manda una sola vez.
 *  - `notifications/initialized` — notificación que ignoramos.
 *  - `tools/list` — devuelve el catálogo declarado por [ToolDefinitions].
 *  - `tools/call` — dispatchea al [ToolHandler] correspondiente.
 *  - `ping` — health-check; devuelve `{}`.
 *
 * Otros métodos devuelven METHOD_NOT_FOUND. La respuesta nunca queda colgada: si el
 * handler lanza, se traduce a `JsonRpcError`.
 */
class McpDispatcher(
    handlers: List<ToolHandler>,
    private val serverName: String = "opentermx-mcp",
    private val serverVersion: String = "0.1.0",
    private val protocolVersion: String = "2024-11-05",
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val handlersByName: Map<String, ToolHandler> = handlers.associateBy { it.definition.name }

    /**
     * Devuelve la respuesta correspondiente al [request]. Si es una notificación (sin id),
     * devuelve `null` y el caller decide no responder por HTTP.
     */
    fun handle(request: JsonRpcRequest): JsonRpcResponse? {
        if (request.isNotification()) {
            log.debug("Notification recibida: {}", request.method)
            return null
        }
        return runCatching {
            when (request.method) {
                "initialize" -> ok(request.id, initializeResult())
                "tools/list" -> ok(request.id, toolsListResult())
                "tools/call" -> handleToolsCall(request)
                "ping" -> ok(request.id, emptyMap<String, Any?>())
                else -> error(
                    request.id,
                    JsonRpcError.METHOD_NOT_FOUND,
                    "Método `${request.method}` no soportado por este servidor MCP",
                )
            }
        }.getOrElse { e ->
            log.warn("Error procesando {}: {}", request.method, e.message, e)
            error(request.id, JsonRpcError.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun handleToolsCall(request: JsonRpcRequest): JsonRpcResponse {
        val params = request.params as? Map<*, *>
            ?: return error(request.id, JsonRpcError.INVALID_PARAMS, "`tools/call` requiere params={name, arguments}")
        val toolName = params["name"] as? String
            ?: return error(request.id, JsonRpcError.INVALID_PARAMS, "Falta `name` en params")
        val handler = handlersByName[toolName]
            ?: return error(request.id, JsonRpcError.METHOD_NOT_FOUND, "Tool desconocida: `$toolName`")
        @Suppress("UNCHECKED_CAST")
        val arguments = (params["arguments"] as? Map<String, Any?>) ?: emptyMap()

        return try {
            // runBlocking: el dispatcher se invoca desde un hilo IO del servidor HTTP;
            // los handlers son suspendibles para esperar el approval gate sin bloquear
            // su propio hilo, pero la conversión a respuesta JSON-RPC sí es sincrónica.
            val payload = runBlocking { handler.invoke(arguments) }
            ok(request.id, toolCallSuccess(payload))
        } catch (e: McpToolException) {
            log.debug("Tool `{}` rechazó input: {}", toolName, e.message)
            ok(request.id, toolCallError(e.message ?: "Error de invocación"))
        } catch (e: Throwable) {
            log.warn("Tool `{}` lanzó excepción inesperada", toolName, e)
            ok(request.id, toolCallError(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun initializeResult(): Map<String, Any?> = linkedMapOf(
        "protocolVersion" to protocolVersion,
        "capabilities" to linkedMapOf(
            "tools" to linkedMapOf("listChanged" to false),
            "logging" to emptyMap<String, Any?>(),
        ),
        "serverInfo" to linkedMapOf(
            "name" to serverName,
            "version" to serverVersion,
        ),
        "instructions" to "Servidor MCP de OpenTermX. Tools: ${handlersByName.keys.joinToString(", ")}.",
    )

    private fun toolsListResult(): Map<String, Any?> {
        val tools = handlersByName.values.map { handler ->
            val def: ToolDef = handler.definition
            linkedMapOf<String, Any?>(
                "name" to def.name,
                "description" to def.description,
                "inputSchema" to def.inputSchema,
            )
        }
        return linkedMapOf("tools" to tools)
    }

    /**
     * Por la convención MCP `tools/call` devuelve `content: [TextContent]` con el payload
     * estructurado serializado como JSON dentro del texto, y `structuredContent` con el
     * mismo payload como objeto (clientes nuevos lo prefieren — los viejos hacen JSON.parse
     * del text).
     */
    private fun toolCallSuccess(payload: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "content" to listOf(
            linkedMapOf(
                "type" to "text",
                "text" to com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload),
            )
        ),
        "structuredContent" to payload,
        "isError" to false,
    )

    private fun toolCallError(message: String): Map<String, Any?> = linkedMapOf(
        "content" to listOf(linkedMapOf("type" to "text", "text" to message)),
        "isError" to true,
    )

    private fun ok(id: Any?, result: Any?) = JsonRpcResponse(id = id, result = result)
    private fun error(id: Any?, code: Int, message: String) =
        JsonRpcResponse(id = id, error = JsonRpcError(code, message))
}