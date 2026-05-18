package com.opentermx.mcp.handlers

import com.opentermx.mcp.tools.ToolDef

/**
 * Contrato común para todos los handlers de tools MCP. Cada implementación maneja una
 * sola tool, identificada por [definition].
 *
 * Los handlers son `suspend` porque la tool mutativa `propose_commands` requiere esperar
 * la decisión del operador en el [com.opentermx.mcp.security.ApprovalGate] sin bloquear
 * el thread del servidor.
 */
interface ToolHandler {

    val definition: ToolDef

    /**
     * Ejecuta la tool con los argumentos ya validados contra el JSON Schema declarado.
     * Devuelve un mapa JSON-compatible con el shape declarado en [ToolDef.outputSchema].
     *
     * Lanza [McpToolException] para errores de input (mensaje destinado al cliente MCP).
     * Errores inesperados burbujean al servidor para traducirse a JSON-RPC error.
     */
    suspend fun invoke(args: Map<String, Any?>): Map<String, Any?>
}

/**
 * Sub-contrato para handlers que necesitan saber a qué `sessionKey` MCP pertenece la
 * llamada. Phase 3 Fase 1 lo introduce para los handlers de Operation Context
 * (`start_operation` / `end_operation` / `current_operation`): la op activa se indexa
 * por sessionKey, no por argumento del cliente.
 *
 * El dispatcher detecta esta interface antes de invocar y pasa el sessionKey real;
 * para tests, la sobrecarga sin sessionKey ofrece un fallback con `"test"`.
 */
interface OperationAwareToolHandler : ToolHandler {

    suspend fun invoke(args: Map<String, Any?>, sessionKey: String): Map<String, Any?>

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> =
        invoke(args, sessionKey = "test")
}

/**
 * Error con mensaje listo para devolver al cliente. Lo usan los handlers para señalar
 * argumentos inválidos, sesiones inexistentes, KB sin índice, etc.
 */
class McpToolException(
    val code: ErrorCode,
    message: String,
) : RuntimeException(message) {

    enum class ErrorCode(val httpStatus: Int, val jsonRpcCode: Int) {
        INVALID_ARGUMENT(400, -32602),
        NOT_FOUND(404, -32004),
        UNAVAILABLE(503, -32001),
    }
}