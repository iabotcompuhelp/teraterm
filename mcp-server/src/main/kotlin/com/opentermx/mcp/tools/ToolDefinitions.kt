package com.opentermx.mcp.tools

/**
 * Catálogo de tools expuestas por el servidor MCP de OpenTermX. Cada [ToolDef] lleva su
 * nombre canónico, descripción en español y los schemas JSON de input/output, lo que
 * permite tanto a los clientes externos (Claude Desktop, Cursor, etc.) como a los tests
 * de integración validar el contrato sin acoplarse a la implementación.
 *
 * Las cuatro tools son las definidas en `.idea/opentermx-mcp.md` § Tarea 2:
 *  - `list_sessions` y `inspect_session` exponen el estado del [com.opentermx.common.ai.SessionRegistry].
 *  - `search_knowledge_base` consulta el índice Lucene del módulo `ai-assistant`.
 *  - `propose_commands` propone comandos para inyectar en una sesión activa y pasa
 *    SIEMPRE por el [com.opentermx.mcp.security.ApprovalGate] antes de tocar la conexión.
 */
data class ToolDef(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>,
    val outputSchema: Map<String, Any?>,
    val mutating: Boolean,
)

object ToolDefinitions {

    val LIST_SESSIONS = ToolDef(
        name = "list_sessions",
        description = "Devuelve las sesiones de terminal activas en OpenTermX (SSH, Telnet, Serial, RawTCP). " +
            "Solo lectura: no toca el dispositivo remoto.",
        inputSchema = obj(
            "type" to "object",
            "properties" to emptyMap<String, Any?>(),
            "additionalProperties" to false,
        ),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("sessions"),
            "properties" to obj(
                "sessions" to obj(
                    "type" to "array",
                    "items" to obj(
                        "type" to "object",
                        "required" to listOf("sessionId", "protocol"),
                        "properties" to obj(
                            "sessionId" to obj("type" to "string"),
                            "protocol" to obj("type" to "string"),
                            "host" to obj("type" to listOf("string", "null")),
                            "port" to obj("type" to listOf("integer", "null")),
                            "username" to obj("type" to listOf("string", "null")),
                            "vendor" to obj("type" to "string"),
                        ),
                    ),
                ),
            ),
        ),
        mutating = false,
    )

    val INSPECT_SESSION = ToolDef(
        name = "inspect_session",
        description = "Devuelve la metadata y las últimas N líneas del buffer de una sesión activa. " +
            "Solo lectura: útil para que el LLM cliente entienda el contexto antes de proponer comandos.",
        inputSchema = obj(
            "type" to "object",
            "required" to listOf("sessionId"),
            "additionalProperties" to false,
            "properties" to obj(
                "sessionId" to obj("type" to "string", "minLength" to 1),
                "lastLines" to obj(
                    "type" to "integer",
                    "minimum" to 1,
                    "maximum" to MAX_LAST_LINES,
                    "default" to DEFAULT_LAST_LINES,
                ),
            ),
        ),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("sessionId", "protocol", "vendor", "lines"),
            "properties" to obj(
                "sessionId" to obj("type" to "string"),
                "protocol" to obj("type" to "string"),
                "host" to obj("type" to listOf("string", "null")),
                "port" to obj("type" to listOf("integer", "null")),
                "username" to obj("type" to listOf("string", "null")),
                "vendor" to obj("type" to "string"),
                "lines" to obj(
                    "type" to "array",
                    "items" to obj("type" to "string"),
                ),
            ),
        ),
        mutating = false,
    )

    val SEARCH_KNOWLEDGE_BASE = ToolDef(
        name = "search_knowledge_base",
        description = "Busca en la base de conocimiento RAG de OpenTermX (índice Lucene) los chunks " +
            "más relevantes a una consulta. Solo lectura.",
        inputSchema = obj(
            "type" to "object",
            "required" to listOf("query"),
            "additionalProperties" to false,
            "properties" to obj(
                "query" to obj("type" to "string", "minLength" to 1),
                "topK" to obj(
                    "type" to "integer",
                    "minimum" to 1,
                    "maximum" to MAX_TOP_K,
                    "default" to DEFAULT_TOP_K,
                ),
            ),
        ),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("hits"),
            "properties" to obj(
                "hits" to obj(
                    "type" to "array",
                    "items" to obj(
                        "type" to "object",
                        "required" to listOf("source", "chunkIndex", "text", "score"),
                        "properties" to obj(
                            "source" to obj("type" to "string"),
                            "chunkIndex" to obj("type" to "integer"),
                            "text" to obj("type" to "string"),
                            "score" to obj("type" to "number"),
                        ),
                    ),
                ),
            ),
        ),
        mutating = false,
    )

    val PROPOSE_COMMANDS = ToolDef(
        name = "propose_commands",
        description = "Propone una lista de comandos para inyectar en una sesión activa. " +
            "Operación mutativa: SIEMPRE abre el diálogo de aprobación con semáforo de riesgo " +
            "(SAFE/CONFIG/DANGEROUS) y solo ejecuta si el operador aprueba.",
        inputSchema = obj(
            "type" to "object",
            "required" to listOf("sessionId", "commands"),
            "additionalProperties" to false,
            "properties" to obj(
                "sessionId" to obj("type" to "string", "minLength" to 1),
                "commands" to obj(
                    "type" to "array",
                    "minItems" to 1,
                    "items" to obj("type" to "string"),
                ),
                "rationale" to obj("type" to "string"),
            ),
        ),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("approved", "executed", "rejected", "auditLogId", "riskSummary"),
            "properties" to obj(
                "approved" to obj("type" to "boolean"),
                "executed" to obj("type" to "integer"),
                "rejected" to obj("type" to "integer"),
                "auditLogId" to obj("type" to "string"),
                "output" to obj("type" to listOf("string", "null")),
                "riskSummary" to obj(
                    "type" to "object",
                    "required" to listOf("safe", "config", "dangerous"),
                    "properties" to obj(
                        "safe" to obj("type" to "integer"),
                        "config" to obj("type" to "integer"),
                        "dangerous" to obj("type" to "integer"),
                    ),
                ),
            ),
        ),
        mutating = true,
    )

    val ALL: List<ToolDef> = listOf(
        LIST_SESSIONS,
        INSPECT_SESSION,
        SEARCH_KNOWLEDGE_BASE,
        PROPOSE_COMMANDS,
    )

    fun byName(name: String): ToolDef? = ALL.firstOrNull { it.name == name }

    const val DEFAULT_LAST_LINES = 50
    const val MAX_LAST_LINES = 500
    const val DEFAULT_TOP_K = 5
    const val MAX_TOP_K = 20

    private fun obj(vararg pairs: Pair<String, Any?>): Map<String, Any?> = linkedMapOf(*pairs)
}