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

    val LIST_MACROS = ToolDef(
        name = "list_macros",
        description = "Lista los macros Groovy disponibles en `~/.opentermx/macros/`. " +
            "Solo lectura.",
        inputSchema = obj("type" to "object", "properties" to emptyMap<String, Any?>(), "additionalProperties" to false),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("macros"),
            "properties" to obj(
                "macros" to obj(
                    "type" to "array",
                    "items" to obj(
                        "type" to "object",
                        "required" to listOf("name"),
                        "properties" to obj(
                            "name" to obj("type" to "string"),
                            "description" to obj("type" to "string"),
                            "parameters" to obj(
                                "type" to "array",
                                "items" to obj(
                                    "type" to "object",
                                    "required" to listOf("name", "type", "required"),
                                    "properties" to obj(
                                        "name" to obj("type" to "string"),
                                        "type" to obj("type" to "string"),
                                        "required" to obj("type" to "boolean"),
                                        "description" to obj("type" to "string"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
        mutating = false,
    )

    val RUN_MACRO = ToolDef(
        name = "run_macro",
        description = "Ejecuta un macro registrado contra una sesión activa. Tool MUTATIVA: " +
            "el operador ve el código del macro en el diálogo de aprobación antes de que se inyecte nada.",
        inputSchema = obj(
            "type" to "object",
            "required" to listOf("macroName"),
            "additionalProperties" to false,
            "properties" to obj(
                "macroName" to obj("type" to "string", "minLength" to 1),
                "parameters" to obj("type" to "object", "additionalProperties" to true),
                "sessionId" to obj("type" to "string"),
            ),
        ),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("approved", "executed", "executionId"),
            "properties" to obj(
                "approved" to obj("type" to "boolean"),
                "executed" to obj("type" to "boolean"),
                "executionId" to obj("type" to "string"),
                "output" to obj("type" to listOf("string", "null")),
                "errors" to obj("type" to "array", "items" to obj("type" to "string")),
            ),
        ),
        mutating = true,
    )

    val TAIL_SESSION = ToolDef(
        name = "tail_session",
        description = "Inicia un stream SSE con la salida en vivo de una sesión activa. " +
            "El servidor emite `notifications/sessions/output` con cada chunk nuevo que " +
            "llega al buffer. Auto-stop a los 30 minutos.",
        inputSchema = obj(
            "type" to "object",
            "required" to listOf("sessionId"),
            "additionalProperties" to false,
            "properties" to obj(
                "sessionId" to obj("type" to "string", "minLength" to 1),
            ),
        ),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("started"),
            "properties" to obj(
                "started" to obj("type" to "boolean"),
                "expiresAtMillis" to obj("type" to "integer"),
                "error" to obj("type" to listOf("string", "null")),
            ),
        ),
        // No es mutativa en el sentido de inyectar comandos, pero abre un side-channel —
        // marcamos `mutating = false` para no requerir aprobación (es solo-lectura del
        // buffer). El operator puede revocar el token si abusa.
        mutating = false,
    )

    val READ_AUDIT_LOG = ToolDef(
        name = "read_audit_log",
        description = "Devuelve entradas del audit log de IA (CSV en `~/.opentermx/audit-ia.csv`). " +
            "Solo lectura, con redacción de credenciales aplicada a comandos y output.",
        inputSchema = obj(
            "type" to "object",
            "additionalProperties" to false,
            "properties" to obj(
                "sessionId" to obj("type" to listOf("string", "null")),
                "sinceMillis" to obj("type" to listOf("integer", "null")),
                "untilMillis" to obj("type" to listOf("integer", "null")),
                "limit" to obj(
                    "type" to "integer",
                    "minimum" to 1,
                    "maximum" to MAX_AUDIT_LIMIT,
                    "default" to DEFAULT_AUDIT_LIMIT,
                ),
            ),
        ),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("entries"),
            "properties" to obj(
                "entries" to obj(
                    "type" to "array",
                    "items" to obj(
                        "type" to "object",
                        "required" to listOf("timestampMillis", "sessionId"),
                        "properties" to obj(
                            "timestampMillis" to obj("type" to "integer"),
                            "sessionId" to obj("type" to "string"),
                            "host" to obj("type" to listOf("string", "null")),
                            "vendor" to obj("type" to listOf("string", "null")),
                            "prompt" to obj("type" to "string"),
                            "commands" to obj("type" to "array", "items" to obj("type" to "string")),
                            "executedCount" to obj("type" to "integer"),
                            "failedCount" to obj("type" to "integer"),
                            "rejected" to obj("type" to "boolean"),
                            "outputTail" to obj("type" to "string"),
                        ),
                    ),
                ),
            ),
        ),
        mutating = false,
    )

    val OPEN_SESSION = ToolDef(
        name = "open_session",
        description = "Abre una sesión nueva al destino indicado. Tool MUTATIVA: el operador " +
            "ve y aprueba el destino antes de conectar. `credentialRef` apunta al keychain " +
            "interno de OpenTermX; passwords en plaintext nunca cruzan MCP.",
        inputSchema = obj(
            "type" to "object",
            "required" to listOf("protocol"),
            "additionalProperties" to false,
            "properties" to obj(
                "protocol" to obj(
                    "type" to "string",
                    "enum" to listOf("SSH", "TELNET", "SERIAL", "RAWTCP"),
                ),
                "host" to obj("type" to listOf("string", "null")),
                "port" to obj("type" to listOf("integer", "null")),
                "username" to obj("type" to listOf("string", "null")),
                "credentialRef" to obj("type" to listOf("string", "null")),
                "label" to obj("type" to listOf("string", "null")),
            ),
        ),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("approved"),
            "properties" to obj(
                "approved" to obj("type" to "boolean"),
                "sessionId" to obj("type" to listOf("string", "null")),
                "error" to obj("type" to listOf("string", "null")),
            ),
        ),
        mutating = true,
    )

    val CLOSE_SESSION = ToolDef(
        name = "close_session",
        description = "Cierra una sesión activa. Tool MUTATIVA: el operador aprueba el cierre " +
            "porque puede interrumpir trabajo en curso.",
        inputSchema = obj(
            "type" to "object",
            "required" to listOf("sessionId"),
            "additionalProperties" to false,
            "properties" to obj(
                "sessionId" to obj("type" to "string", "minLength" to 1),
                "reason" to obj("type" to listOf("string", "null")),
            ),
        ),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("approved", "closed"),
            "properties" to obj(
                "approved" to obj("type" to "boolean"),
                "closed" to obj("type" to "boolean"),
                "error" to obj("type" to listOf("string", "null")),
            ),
        ),
        mutating = true,
    )

    val START_OPERATION = ToolDef(
        name = "start_operation",
        description = "Inicia una operación estructurada (Phase 3 Fase 1). Recibe el context como " +
            "YAML/JSON inline o como path al archivo. Valida contra el schema y persiste. Mientras " +
            "haya una op activa para esta sesión MCP, cada tool_result lleva un bloque " +
            "[OPERATION CONTEXT ...] inyectado, y los comandos que violen scope.forbidden_commands / " +
            "scope.allowed_commands_prefix son rechazados antes de tocar el device.",
        inputSchema = obj(
            "type" to "object",
            "additionalProperties" to false,
            "oneOf" to listOf(
                obj("required" to listOf("contextPath")),
                obj("required" to listOf("contextInline")),
                obj("required" to listOf("contextYaml")),
            ),
            "properties" to obj(
                "contextPath" to obj("type" to "string", "minLength" to 1,
                    "description" to "Path absoluto a un archivo .yaml/.yml/.json con el context."),
                "contextInline" to obj("type" to "object",
                    "description" to "Context inline como objeto JSON."),
                "contextYaml" to obj("type" to "string", "minLength" to 1,
                    "description" to "Context inline como string YAML."),
            ),
        ),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("operationId", "startedAtMillis"),
            "properties" to obj(
                "operationId" to obj("type" to "string"),
                "startedAtMillis" to obj("type" to "integer"),
                "description" to obj("type" to "string"),
            ),
        ),
        mutating = false,
    )

    val END_OPERATION = ToolDef(
        name = "end_operation",
        description = "Cierra la operación activa de esta sesión MCP. Devuelve summary (duración, descripción). " +
            "Tras cerrar, las próximas tools de esta sesión ya no llevan el bloque de context inyectado.",
        inputSchema = obj(
            "type" to "object",
            "required" to listOf("operationId"),
            "additionalProperties" to false,
            "properties" to obj(
                "operationId" to obj("type" to "string", "minLength" to 1),
            ),
        ),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("operationId", "durationMillis"),
            "properties" to obj(
                "operationId" to obj("type" to "string"),
                "durationMillis" to obj("type" to "integer"),
                "description" to obj("type" to "string"),
            ),
        ),
        mutating = false,
    )

    val CURRENT_OPERATION = ToolDef(
        name = "current_operation",
        description = "Devuelve el context de la operación activa de esta sesión MCP, o null si no hay. " +
            "Útil para que el cliente recupere el detalle si perdió el handle.",
        inputSchema = obj(
            "type" to "object",
            "properties" to emptyMap<String, Any?>(),
            "additionalProperties" to false,
        ),
        outputSchema = obj(
            "type" to "object",
            "properties" to obj(
                "operationId" to obj("type" to listOf("string", "null")),
                "context" to obj("type" to listOf("object", "null")),
            ),
        ),
        mutating = false,
    )

    val ALL: List<ToolDef> = listOf(
        LIST_SESSIONS,
        INSPECT_SESSION,
        SEARCH_KNOWLEDGE_BASE,
        PROPOSE_COMMANDS,
        LIST_MACROS,
        RUN_MACRO,
        OPEN_SESSION,
        CLOSE_SESSION,
        READ_AUDIT_LOG,
        TAIL_SESSION,
        START_OPERATION,
        END_OPERATION,
        CURRENT_OPERATION,
    )

    fun byName(name: String): ToolDef? = ALL.firstOrNull { it.name == name }

    const val DEFAULT_LAST_LINES = 50
    const val MAX_LAST_LINES = 500
    const val DEFAULT_TOP_K = 5
    const val MAX_TOP_K = 20
    const val DEFAULT_AUDIT_LIMIT = 50
    const val MAX_AUDIT_LIMIT = 200

    private fun obj(vararg pairs: Pair<String, Any?>): Map<String, Any?> = linkedMapOf(*pairs)
}