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
                "approvalToken" to obj(
                    "type" to listOf("string", "null"),
                    "description" to "Phase 3 Fase 3: token HMAC emitido por `compliance_evaluate`. " +
                        "Obligatorio cuando la operation activa declara `require_compliance_approval: true`.",
                ),
                "deviceAlias" to obj(
                    "type" to listOf("string", "null"),
                    "description" to "Alias del target. Si viene, se valida contra el scope del approval_token.",
                ),
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

    val RUN_READONLY_COMMAND = ToolDef(
        name = "run_readonly_command",
        description = "Ejecuta UN comando de SOLO LECTURA (show/display/get, ping, traceroute) en una " +
            "sesión activa, validado contra una whitelist estricta por vendor que rechaza metacaracteres, " +
            "encadenadores y pipes a comandos de escritura. Si el server tiene auto-aprobación read-only " +
            "activa, no abre diálogo; si no, el operador aprueba con un click. Para cualquier comando " +
            "mutativo usar `propose_commands` (gate obligatorio).",
        inputSchema = obj(
            "type" to "object",
            "required" to listOf("sessionId", "command"),
            "additionalProperties" to false,
            "properties" to obj(
                "sessionId" to obj("type" to "string", "minLength" to 1),
                "command" to obj(
                    "type" to "string",
                    "minLength" to 1,
                    "description" to "Un único comando read-only. Sin newlines, `;`, `&&`, redirecciones " +
                        "ni pipes a redirect/tee/save — la whitelist los rechaza.",
                ),
                "lastLines" to obj(
                    "type" to "integer",
                    "minimum" to 1,
                    "maximum" to MAX_LAST_LINES,
                    "default" to DEFAULT_LAST_LINES,
                    "description" to "Cuántas líneas del buffer devolver como output tras ejecutar.",
                ),
                "rationale" to obj("type" to "string"),
            ),
        ),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("approved", "autoApproved", "executed", "auditLogId"),
            "properties" to obj(
                "approved" to obj("type" to "boolean"),
                "autoApproved" to obj("type" to "boolean"),
                "executed" to obj("type" to "integer"),
                "vendor" to obj("type" to "string"),
                "auditLogId" to obj("type" to "string"),
                "output" to obj("type" to listOf("string", "null")),
            ),
        ),
        // Inyecta en la sesión (aunque solo comandos de lectura), así que el modo
        // read-only ESTRICTO del server también la bloquea: `mutating = true` deja esa
        // decisión en manos del operador. La whitelist es la que garantiza que no se
        // pueda escribir configuración a través de esta tool.
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
            "interno de OpenTermX; passwords en plaintext nunca cruzan MCP. " +
            "Phase 3 Fase 2: como alternativa al par `host`+`port` se puede pasar `deviceAlias` " +
            "y el servidor resuelve el destino (protocol/host/port/username/credentials) desde " +
            "el Device Registry. `protocol` queda opcional cuando viene `deviceAlias`.",
        inputSchema = obj(
            "type" to "object",
            "additionalProperties" to false,
            "anyOf" to listOf(
                obj("required" to listOf("protocol")),
                obj("required" to listOf("deviceAlias")),
            ),
            "properties" to obj(
                "protocol" to obj(
                    "type" to listOf("string", "null"),
                    "enum" to listOf("SSH", "TELNET", "SERIAL", "RAWTCP", null),
                ),
                "host" to obj("type" to listOf("string", "null")),
                "port" to obj("type" to listOf("integer", "null")),
                "username" to obj("type" to listOf("string", "null")),
                "credentialRef" to obj("type" to listOf("string", "null")),
                "label" to obj("type" to listOf("string", "null")),
                "deviceAlias" to obj(
                    "type" to listOf("string", "null"),
                    "description" to "Alias del Device Registry. Si viene, sobrescribe protocol/host/port/username con los del inventario.",
                ),
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
                    "description" to "Path absoluto a un archivo .yaml/.yml/.json con el context. " +
                        "Debe estar dentro de `~/.opentermx/`; para archivos fuera de ese directorio usar contextInline/contextYaml."),
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

    val INVENTORY_LIST = ToolDef(
        name = "inventory_list",
        description = "Lista devices del inventario (entradas de Saved Connections con `alias` definido). " +
            "Filtra por tags, groups o deviceType (AND entre filtros, ANY-of dentro). Nunca devuelve " +
            "credenciales — los `secret` y `keyPath` se quedan en el server.",
        inputSchema = obj(
            "type" to "object",
            "additionalProperties" to false,
            "properties" to obj(
                "tags" to obj(
                    "type" to listOf("array", "null"),
                    "items" to obj("type" to "string"),
                    "description" to "Una entrada matchea si tiene al menos uno de estos tags.",
                ),
                "groups" to obj(
                    "type" to listOf("array", "null"),
                    "items" to obj("type" to "string"),
                ),
                "deviceType" to obj("type" to listOf("string", "null")),
            ),
        ),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("devices"),
            "properties" to obj(
                "devices" to obj(
                    "type" to "array",
                    "items" to obj(
                        "type" to "object",
                        "required" to listOf("alias", "protocol", "host", "port", "username"),
                        "properties" to obj(
                            "alias" to obj("type" to "string"),
                            "protocol" to obj("type" to "string"),
                            "host" to obj("type" to "string"),
                            "port" to obj("type" to "integer"),
                            "username" to obj("type" to "string"),
                            "deviceType" to obj("type" to listOf("string", "null")),
                            "tags" to obj("type" to "array", "items" to obj("type" to "string")),
                            "groups" to obj("type" to "array", "items" to obj("type" to "string")),
                            "displayLabel" to obj("type" to "string"),
                            "hasActiveSession" to obj("type" to "boolean"),
                        ),
                    ),
                ),
            ),
        ),
        mutating = false,
    )

    val INVENTORY_DESCRIBE = ToolDef(
        name = "inventory_describe",
        description = "Devuelve la metadata completa de un device del inventario por alias. " +
            "Incluye `hasActiveSession` cuando hay una sesión abierta hacia ese host:port. " +
            "Nunca devuelve credenciales.",
        inputSchema = obj(
            "type" to "object",
            "required" to listOf("alias"),
            "additionalProperties" to false,
            "properties" to obj(
                "alias" to obj("type" to "string", "minLength" to 1),
            ),
        ),
        outputSchema = obj(
            "type" to "object",
            "properties" to obj(
                "device" to obj("type" to listOf("object", "null")),
                "activeSessionId" to obj("type" to listOf("string", "null")),
            ),
        ),
        mutating = false,
    )

    val COMPLIANCE_EVALUATE = ToolDef(
        name = "compliance_evaluate",
        description = "Evalúa una propuesta de comandos contra el operation context activo. " +
            "Solo accesible para clientes con rol COMPLIANCE (header `X-OpenTermX-Role: COMPLIANCE`). " +
            "Si `approved=true`, devuelve un `approvalToken` HMAC-SHA256 que el rol OPERATOR debe " +
            "incluir en `propose_commands` cuando la operation declara `require_compliance_approval`. " +
            "El token expira en `ttlMillis` (default 15 min, máx 60).",
        inputSchema = obj(
            "type" to "object",
            "required" to listOf("operationId", "proposedCommands"),
            "additionalProperties" to false,
            "properties" to obj(
                "operationId" to obj("type" to "string", "minLength" to 1),
                "proposedCommands" to obj(
                    "type" to "array",
                    "items" to obj("type" to "string"),
                    "minItems" to 1,
                ),
                "deviceAlias" to obj("type" to listOf("string", "null"),
                    "description" to "Si se especifica, el token solo es válido para este alias del Device Registry."),
                "reasons" to obj("type" to listOf("array", "null"),
                    "items" to obj("type" to "string"),
                    "description" to "Razones del compliance LLM para aprobar/rechazar (auditadas)."),
                "approved" to obj("type" to "boolean",
                    "description" to "Decisión del compliance. Si es false, no se emite token."),
                "ttlMillis" to obj("type" to listOf("integer", "null"),
                    "minimum" to 1000, "maximum" to 3600000,
                    "description" to "TTL del token en ms. Default 900000 (15 min). Clamp a [1s, 60min]."),
            ),
        ),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("approved"),
            "properties" to obj(
                "approved" to obj("type" to "boolean"),
                "approvalToken" to obj("type" to listOf("string", "null")),
                "reasons" to obj("type" to "array", "items" to obj("type" to "string")),
                "auditLogId" to obj("type" to listOf("string", "null")),
            ),
        ),
        mutating = false,
    )

    val SNAPSHOT_CREATE = ToolDef(
        name = "snapshot_create",
        description = "Captura el estado actual del buffer de una sesión como snapshot. " +
            "El cliente LLM debe haber ejecutado antes los comandos canónicos (ej. " +
            "`show running-config`) — esta tool NO los inyecta; solo congela el output. " +
            "Phase 3 Fase 4. Persiste bajo `~/.opentermx/operations/<op-id>/snapshots/`.",
        inputSchema = obj(
            "type" to "object",
            "required" to listOf("sessionId", "snapshotType"),
            "additionalProperties" to false,
            "properties" to obj(
                "sessionId" to obj("type" to "string", "minLength" to 1),
                "snapshotType" to obj(
                    "type" to "string",
                    "description" to "running_config / interfaces_status / routing_table / custom / buffer_tail. " +
                        "Solo label semántico — el contenido es siempre las últimas N líneas del buffer.",
                ),
                "lastLines" to obj(
                    "type" to "integer", "minimum" to 1, "maximum" to MAX_SNAPSHOT_LINES,
                    "default" to DEFAULT_SNAPSHOT_LINES,
                ),
                "deviceAlias" to obj("type" to listOf("string", "null")),
                "label" to obj("type" to listOf("string", "null")),
            ),
        ),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("snapshotId", "contentHash", "lineCount"),
            "properties" to obj(
                "snapshotId" to obj("type" to "string"),
                "contentHash" to obj("type" to "string"),
                "lineCount" to obj("type" to "integer"),
                "operationId" to obj("type" to listOf("string", "null")),
            ),
        ),
        mutating = false,
    )

    val SNAPSHOT_DIFF = ToolDef(
        name = "snapshot_diff",
        description = "Calcula el diff line-based entre dos snapshots. Para Cisco IOS y " +
            "vendors similares (hpe_comware, huawei_vrp) agrupa cambios por sección de config. " +
            "Phase 3 Fase 4.",
        inputSchema = obj(
            "type" to "object",
            "required" to listOf("snapshotIdBefore", "snapshotIdAfter"),
            "additionalProperties" to false,
            "properties" to obj(
                "snapshotIdBefore" to obj("type" to "string", "minLength" to 1),
                "snapshotIdAfter" to obj("type" to "string", "minLength" to 1),
                "deviceType" to obj("type" to listOf("string", "null"),
                    "description" to "Override del deviceType para selección de heurística de agrupación."),
            ),
        ),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("summary", "addedLines", "removedLines", "identicalLineCount", "sections"),
            "properties" to obj(
                "summary" to obj("type" to "string"),
                "addedLines" to obj("type" to "array", "items" to obj("type" to "string")),
                "removedLines" to obj("type" to "array", "items" to obj("type" to "string")),
                "identicalLineCount" to obj("type" to "integer"),
                "sections" to obj("type" to "array", "items" to obj("type" to "object")),
            ),
        ),
        mutating = false,
    )

    val SNAPSHOT_COMPARE_TO_CRITERIA = ToolDef(
        name = "snapshot_compare_to_criteria",
        description = "Evalúa los `success_criteria` declarados en la operation activa contra " +
            "un snapshot post-cambio. Devuelve PASS / FAIL / WARN por criterio + overall. " +
            "Phase 3 Fase 4.",
        inputSchema = obj(
            "type" to "object",
            "required" to listOf("snapshotIdAfter"),
            "additionalProperties" to false,
            "properties" to obj(
                "snapshotIdAfter" to obj("type" to "string", "minLength" to 1),
                "operationId" to obj("type" to listOf("string", "null"),
                    "description" to "Override del operationId — útil para validators que evalúan ops ya cerradas."),
            ),
        ),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("overall", "summary", "results"),
            "properties" to obj(
                "overall" to obj("type" to "string"),
                "summary" to obj("type" to "string"),
                "results" to obj("type" to "array", "items" to obj("type" to "object")),
            ),
        ),
        mutating = false,
    )

    val ROLLBACK_PROPOSE = ToolDef(
        name = "rollback_propose",
        description = "Genera una lista sugerida de comandos para revertir un device al estado " +
            "del `snapshotIdBefore` desde el actual `snapshotIdAfter`. NO ejecuta nada — " +
            "devuelve los comandos al operator/compliance loop. Phase 3 Fase 4.",
        inputSchema = obj(
            "type" to "object",
            "required" to listOf("snapshotIdBefore", "snapshotIdAfter"),
            "additionalProperties" to false,
            "properties" to obj(
                "snapshotIdBefore" to obj("type" to "string", "minLength" to 1),
                "snapshotIdAfter" to obj("type" to "string", "minLength" to 1),
                "deviceType" to obj("type" to listOf("string", "null")),
            ),
        ),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("supported", "commands", "notes"),
            "properties" to obj(
                "supported" to obj("type" to "boolean"),
                "commands" to obj("type" to "array", "items" to obj("type" to "string")),
                "notes" to obj("type" to "array", "items" to obj("type" to "string")),
                "deviceType" to obj("type" to listOf("string", "null")),
            ),
        ),
        mutating = false,
    )

    val POLICY_LOAD = ToolDef(
        name = "policy_load",
        description = "Carga una policy desde archivo YAML/JSON o inline. Phase 3 Fase 5. " +
            "La policy queda registrada en memoria del server hasta el próximo restart. " +
            "Si una policy con el mismo `name` ya estaba cargada, se reemplaza.",
        inputSchema = obj(
            "type" to "object",
            "additionalProperties" to false,
            "oneOf" to listOf(
                obj("required" to listOf("path")),
                obj("required" to listOf("yaml")),
            ),
            "properties" to obj(
                "path" to obj("type" to "string", "minLength" to 1,
                    "description" to "Path absoluto a un .yaml/.yml/.json."),
                "yaml" to obj("type" to "string", "minLength" to 1,
                    "description" to "Policy inline como string YAML."),
            ),
        ),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("name", "version", "ruleCount"),
            "properties" to obj(
                "name" to obj("type" to "string"),
                "version" to obj("type" to "string"),
                "ruleCount" to obj("type" to "integer"),
            ),
        ),
        mutating = false,
    )

    val POLICY_LIST = ToolDef(
        name = "policy_list",
        description = "Lista las policies cargadas en memoria del server. Phase 3 Fase 5.",
        inputSchema = obj(
            "type" to "object",
            "additionalProperties" to false,
            "properties" to emptyMap<String, Any?>(),
        ),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("policies"),
            "properties" to obj(
                "policies" to obj(
                    "type" to "array",
                    "items" to obj(
                        "type" to "object",
                        "required" to listOf("name", "version", "ruleCount"),
                        "properties" to obj(
                            "name" to obj("type" to "string"),
                            "version" to obj("type" to "string"),
                            "ruleCount" to obj("type" to "integer"),
                            "deviceTypes" to obj("type" to "array", "items" to obj("type" to "string")),
                        ),
                    ),
                ),
            ),
        ),
        mutating = false,
    )

    val POLICY_EVALUATE = ToolDef(
        name = "policy_evaluate",
        description = "Evalúa una policy registrada contra el snapshot más reciente del device. " +
            "Resultado 100% determinístico — sin LLM. Phase 3 Fase 5.",
        inputSchema = obj(
            "type" to "object",
            "required" to listOf("policyName"),
            "additionalProperties" to false,
            "properties" to obj(
                "policyName" to obj("type" to "string", "minLength" to 1),
                "deviceAlias" to obj("type" to listOf("string", "null"),
                    "description" to "Alias del Device Registry. Si se omite, usar `snapshotId`."),
                "snapshotId" to obj("type" to listOf("string", "null"),
                    "description" to "Override: evaluar contra este snapshot específico."),
                "markdown" to obj("type" to "boolean", "default" to false,
                    "description" to "Si true, incluye un campo `markdown` listo para pegar en ticket."),
            ),
        ),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("policyName", "results"),
            "properties" to obj(
                "policyName" to obj("type" to "string"),
                "policyVersion" to obj("type" to "string"),
                "deviceAlias" to obj("type" to listOf("string", "null")),
                "passCount" to obj("type" to "integer"),
                "failCount" to obj("type" to "integer"),
                "warnCount" to obj("type" to "integer"),
                "results" to obj("type" to "array", "items" to obj("type" to "object")),
                "markdown" to obj("type" to listOf("string", "null")),
            ),
        ),
        mutating = false,
    )

    val POLICY_AUDIT = ToolDef(
        name = "policy_audit",
        description = "Corre una policy contra todos los devices del Device Registry que matchean " +
            "filtros + `applies_to` de la policy. Reporte agregado JSON o Markdown. Phase 3 Fase 5.",
        inputSchema = obj(
            "type" to "object",
            "required" to listOf("policyName"),
            "additionalProperties" to false,
            "properties" to obj(
                "policyName" to obj("type" to "string", "minLength" to 1),
                "tagsAny" to obj("type" to listOf("array", "null"),
                    "items" to obj("type" to "string")),
                "markdown" to obj("type" to "boolean", "default" to false),
            ),
        ),
        outputSchema = obj(
            "type" to "object",
            "required" to listOf("policyName", "deviceCount", "byDevice"),
            "properties" to obj(
                "policyName" to obj("type" to "string"),
                "deviceCount" to obj("type" to "integer"),
                "totalFail" to obj("type" to "integer"),
                "totalWarn" to obj("type" to "integer"),
                "byDevice" to obj("type" to "array", "items" to obj("type" to "object")),
                "markdown" to obj("type" to listOf("string", "null")),
            ),
        ),
        mutating = false,
    )

    val ALL: List<ToolDef> = listOf(
        LIST_SESSIONS,
        INSPECT_SESSION,
        SEARCH_KNOWLEDGE_BASE,
        PROPOSE_COMMANDS,
        RUN_READONLY_COMMAND,
        LIST_MACROS,
        RUN_MACRO,
        OPEN_SESSION,
        CLOSE_SESSION,
        READ_AUDIT_LOG,
        TAIL_SESSION,
        START_OPERATION,
        END_OPERATION,
        CURRENT_OPERATION,
        INVENTORY_LIST,
        INVENTORY_DESCRIBE,
        COMPLIANCE_EVALUATE,
        SNAPSHOT_CREATE,
        SNAPSHOT_DIFF,
        SNAPSHOT_COMPARE_TO_CRITERIA,
        ROLLBACK_PROPOSE,
        POLICY_LOAD,
        POLICY_LIST,
        POLICY_EVALUATE,
        POLICY_AUDIT,
    )

    fun byName(name: String): ToolDef? = ALL.firstOrNull { it.name == name }

    const val DEFAULT_LAST_LINES = 50
    const val MAX_LAST_LINES = 500
    const val DEFAULT_TOP_K = 5
    const val MAX_TOP_K = 20
    const val DEFAULT_AUDIT_LIMIT = 50
    const val MAX_AUDIT_LIMIT = 200
    const val DEFAULT_SNAPSHOT_LINES = 500
    const val MAX_SNAPSHOT_LINES = 10_000

    private fun obj(vararg pairs: Pair<String, Any?>): Map<String, Any?> = linkedMapOf(*pairs)
}