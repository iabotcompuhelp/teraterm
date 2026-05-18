package com.opentermx.mcp.protocol

import com.opentermx.mcp.handlers.McpToolException
import com.opentermx.mcp.handlers.ToolHandler
import com.opentermx.mcp.tools.ToolDef
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Implementa el subset del protocolo MCP (JSON-RPC 2.0) necesario para clientes tipo
 * Claude Desktop, Cursor o Claude Code:
 *
 *  - `initialize` — devuelve capabilities + serverInfo; negocia `protocolVersion`.
 *  - `notifications/initialized` — notificación que ignoramos.
 *  - `tools/list` — devuelve el catálogo declarado por [ToolDefinitions].
 *  - `tools/call` — dispatchea al [ToolHandler] correspondiente.
 *  - `ping` — health-check; devuelve `{}`.
 *
 * Tras `initialize`, los requests subsiguientes (excepto `ping` y notifications) deben
 * traer el header `MCP-Protocol-Version` matcheando la versión negociada. Esto sigue el
 * MCP spec § Lifecycle.
 */
class McpDispatcher(
    handlers: List<ToolHandler>,
    private val serverName: String = "opentermx-mcp",
    private val serverVersion: String = com.opentermx.mcp.BuildInfo.VERSION,
    private val supportedProtocolVersions: List<String> = SUPPORTED_VERSIONS,
    private val readOnly: Boolean = false,
    private val allowedSessionGlob: String? = null,
    private val resourceProvider: ResourceProvider = ResourceProvider.Empty,
    private val promptProvider: PromptProvider = PromptProvider.Default,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val handlersByName: Map<String, ToolHandler> = handlers.associateBy { it.definition.name }
    private val negotiatedVersions = ConcurrentHashMap<String, String>()

    /** Última versión soportada — la que reportamos a clientes que piden una desconocida. */
    val latestProtocolVersion: String get() = supportedProtocolVersions.last()

    /**
     * Devuelve la respuesta correspondiente al [request].
     *
     * @param transport contexto del transporte HTTP. `sessionKey` identifica al cliente
     *   (típicamente IP + bearer token); `protocolVersionHeader` es el valor del header
     *   `MCP-Protocol-Version` (null si no vino). Para tests que ejercitan handlers en
     *   aislado, los defaults eluden la validación.
     */
    fun handle(
        request: JsonRpcRequest,
        transport: TransportContext = TransportContext.test(),
    ): JsonRpcResponse? {
        if (request.isNotification()) {
            log.debug("Notification recibida: {}", request.method)
            return null
        }
        // Negociación / validación de versión MCP antes del dispatch real.
        if (request.method == "initialize") {
            return initializeAndNegotiate(request, transport)
        }
        if (transport.enforceVersionHeader && !exemptFromVersionCheck(request.method)) {
            val violation = checkVersionHeader(transport)
            if (violation != null) return violation
        }
        return runCatching {
            when (request.method) {
                "tools/list" -> ok(request.id, toolsListResult())
                "tools/call" -> handleToolsCall(request)
                "resources/list" -> ok(request.id, resourcesListResult())
                "resources/read" -> handleResourcesRead(request)
                "prompts/list" -> ok(request.id, promptsListResult())
                "prompts/get" -> handlePromptsGet(request)
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

    private fun initializeAndNegotiate(request: JsonRpcRequest, transport: TransportContext): JsonRpcResponse {
        val params = request.params as? Map<*, *>
        val requested = params?.get("protocolVersion") as? String
        // Cliente que no manda version (más viejo) → asumimos lo más compat (la primera).
        // Cliente que pide una version desconocida → respondemos con la última que conocemos.
        val negotiated = when {
            requested == null -> supportedProtocolVersions.first()
            requested in supportedProtocolVersions -> requested
            else -> latestProtocolVersion
        }
        negotiatedVersions[transport.sessionKey] = negotiated
        return ok(request.id, initializeResult(negotiated))
    }

    private fun checkVersionHeader(transport: TransportContext): JsonRpcResponse? {
        val header = transport.protocolVersionHeader
        val negotiated = negotiatedVersions[transport.sessionKey]
        if (negotiated == null) {
            return error(
                id = null,
                code = JsonRpcError.INVALID_REQUEST,
                message = "Cliente no inicializado: enviar `initialize` antes de cualquier otra llamada",
            )
        }
        if (header == null) {
            return error(
                id = null,
                code = JsonRpcError.INVALID_REQUEST,
                message = "Falta header `MCP-Protocol-Version` (esperado `$negotiated`)",
            )
        }
        if (header != negotiated) {
            return error(
                id = null,
                code = JsonRpcError.INVALID_REQUEST,
                message = "Header `MCP-Protocol-Version=$header` no coincide con la versión negociada `$negotiated`",
            )
        }
        return null
    }

    private fun exemptFromVersionCheck(method: String): Boolean =
        method == "ping" || method.startsWith("notifications/")

    private fun handleToolsCall(request: JsonRpcRequest): JsonRpcResponse {
        val params = request.params as? Map<*, *>
            ?: return error(request.id, JsonRpcError.INVALID_PARAMS, "`tools/call` requiere params={name, arguments}")
        val toolName = params["name"] as? String
            ?: return error(request.id, JsonRpcError.INVALID_PARAMS, "Falta `name` en params")
        val handler = handlersByName[toolName]
            ?: return error(request.id, JsonRpcError.METHOD_NOT_FOUND, "Tool desconocida: `$toolName`")
        // Short-circuit de read-only: las mutativas no llegan al handler.
        if (readOnly && handler.definition.mutating) {
            return error(
                request.id,
                JsonRpcError.METHOD_NOT_FOUND,
                "Tool `$toolName` deshabilitada: el servidor está en modo read-only",
            )
        }
        @Suppress("UNCHECKED_CAST")
        val arguments = (params["arguments"] as? Map<String, Any?>) ?: emptyMap()
        // ACL por sessionId: si el handler recibe un sessionId y existe un glob
        // restrictivo configurado, validamos antes de invocar.
        val sessionIdArg = arguments["sessionId"] as? String
        if (sessionIdArg != null && !com.opentermx.mcp.security.GlobMatcher.matches(allowedSessionGlob, sessionIdArg)) {
            return error(
                request.id,
                JsonRpcError.INVALID_PARAMS,
                "Sesión `$sessionIdArg` fuera del scope permitido (glob=`$allowedSessionGlob`)",
            )
        }

        return try {
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

    private fun initializeResult(negotiatedVersion: String): Map<String, Any?> = linkedMapOf(
        "protocolVersion" to negotiatedVersion,
        "capabilities" to linkedMapOf(
            "tools" to linkedMapOf("listChanged" to false),
            "resources" to linkedMapOf("listChanged" to false, "subscribe" to false),
            "prompts" to linkedMapOf("listChanged" to false),
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

    private fun resourcesListResult(): Map<String, Any?> {
        val list = resourceProvider.list().map { r ->
            linkedMapOf<String, Any?>(
                "uri" to r.uri,
                "name" to r.name,
                "description" to r.description,
                "mimeType" to r.mimeType,
            )
        }
        return linkedMapOf("resources" to list)
    }

    private fun handleResourcesRead(request: JsonRpcRequest): JsonRpcResponse {
        val params = request.params as? Map<*, *>
            ?: return error(request.id, JsonRpcError.INVALID_PARAMS, "`resources/read` requiere `uri`")
        val uri = params["uri"] as? String
            ?: return error(request.id, JsonRpcError.INVALID_PARAMS, "Falta `uri`")
        val content = resourceProvider.read(uri)
            ?: return error(request.id, JsonRpcError.METHOD_NOT_FOUND, "Resource `$uri` desconocido")
        return ok(request.id, linkedMapOf(
            "contents" to listOf(
                linkedMapOf(
                    "uri" to content.uri,
                    "mimeType" to content.mimeType,
                    "text" to content.text,
                )
            )
        ))
    }

    private fun promptsListResult(): Map<String, Any?> {
        val list = promptProvider.list().map { p ->
            linkedMapOf<String, Any?>(
                "name" to p.name,
                "description" to p.description,
                "arguments" to p.arguments.map { a ->
                    linkedMapOf("name" to a.name, "description" to a.description, "required" to a.required)
                },
            )
        }
        return linkedMapOf("prompts" to list)
    }

    private fun handlePromptsGet(request: JsonRpcRequest): JsonRpcResponse {
        val params = request.params as? Map<*, *>
            ?: return error(request.id, JsonRpcError.INVALID_PARAMS, "`prompts/get` requiere `name`")
        val name = params["name"] as? String
            ?: return error(request.id, JsonRpcError.INVALID_PARAMS, "Falta `name`")
        @Suppress("UNCHECKED_CAST")
        val arguments = (params["arguments"] as? Map<String, Any?>) ?: emptyMap()
        val materialized = promptProvider.get(name, arguments)
            ?: return error(request.id, JsonRpcError.METHOD_NOT_FOUND, "Prompt `$name` desconocido")
        return ok(request.id, linkedMapOf(
            "description" to materialized.description,
            "messages" to materialized.messages.map { it.toMcp() },
        ))
    }

    private fun ok(id: Any?, result: Any?) = JsonRpcResponse(id = id, result = result)
    private fun error(id: Any?, code: Int, message: String) =
        JsonRpcResponse(id = id, error = JsonRpcError(code, message))

    companion object {
        /** Versiones MCP soportadas — ordenadas de más vieja a más nueva. */
        val SUPPORTED_VERSIONS: List<String> = listOf("2024-11-05", "2025-03-26")
    }
}

/**
 * Contexto de transporte que el [McpServer] inyecta al dispatcher con cada request.
 * Centraliza los datos que vienen del HTTP/SSE (sessionKey, header de protocol version)
 * para que el dispatcher pueda hacer negociación sin acoplarse a Javalin.
 */
data class TransportContext(
    val sessionKey: String,
    val protocolVersionHeader: String?,
    val enforceVersionHeader: Boolean,
) {
    companion object {
        /** Contexto que se usa en tests unitarios que no ejercitan version negotiation. */
        fun test(): TransportContext = TransportContext("test", null, enforceVersionHeader = false)
    }
}