package com.opentermx.mcp.protocol

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Representación mínima de un mensaje JSON-RPC 2.0 (requests y notifications). Los
 * `notifications` (sin `id`) los reconocemos por [id] == null.
 *
 * No usamos kotlinx-serialization para mantener consistencia con el resto del repo
 * (Jackson). El parser tolera el formato relajado de los clientes MCP (Claude Desktop
 * envía `params` como object o array según el método).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Any? = null,
    val method: String,
    val params: Any? = null,
) {
    fun isNotification(): Boolean = id == null
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Any? = null,
    val result: Any? = null,
    val error: JsonRpcError? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null,
) {
    companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
    }
}