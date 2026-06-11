package com.opentermx.mcp.security

/**
 * Phase 3 Fase 3 — Multi-agent roles.
 *
 * Cada conexión MCP declara qué "persona" representa vía header HTTP `X-OpenTermX-Role`.
 * Cuando el header falta el rol asumido es [OPERATOR] (back-compat con clientes pre-Fase 3).
 * El dispatcher consulta [RoleAccessControl.allows] antes de invocar una tool y rechaza con
 * `-32601 Method not found` cualquier intento fuera del scope del rol.
 *
 * El enforcement es server-side: no es suficiente decirle al LLM "no uses esta tool",
 * cada request lleva el rol y el servidor verifica.
 */
enum class Role {
    /**
     * Persona LLM que propone y ejecuta comandos. Tiene acceso a las tools de descubrimiento
     * (inventory_*, list_sessions, inspect_session) y de mutación (open/close_session,
     * propose_commands, run_macro). Las mutaciones siguen pasando por el [ApprovalGate]
     * humano y, cuando la operation activa lo exige, también por un [ApprovalToken]
     * firmado por COMPLIANCE.
     */
    OPERATOR,

    /**
     * Persona LLM cuyo único trabajo es evaluar propuestas. NO ejecuta nada en devices.
     * Tiene acceso a tools de lectura (inspect_session, read_audit_log, inventory_*) y a
     * la tool específica `compliance_evaluate` que emite [ApprovalToken] firmados.
     */
    COMPLIANCE,

    /**
     * Persona LLM que valida un cambio DESPUÉS de aplicado: compara snapshots, evalúa
     * success_criteria del context, decide success/partial/fail. Tools de lectura y
     * snapshots (Fase 4). No ejecuta y no aprueba.
     */
    VALIDATOR;

    companion object {
        /** Resuelve un rol desde el header HTTP. Acepta case-insensitive; default OPERATOR. */
        fun fromHeader(raw: String?): Role {
            if (raw.isNullOrBlank()) return OPERATOR
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: OPERATOR
        }
    }
}

/**
 * Whitelists por rol. Hardcoded a propósito: el catálogo de tools del MCP es chico y
 * estable, y queremos que cualquier cambio de scope sea un PR explícito (no algo que se
 * lea de un settings file que un atacante pueda manipular).
 *
 * Las tools que NO están en ningún whitelist quedan inaccesibles para todos los roles.
 * Los métodos de "transporte" del MCP — `initialize`, `ping`, `tools/list`,
 * `resources/list`, `resources/read`, `prompts/list`, `prompts/get` — están siempre
 * permitidos; el filtro solo aplica al routing de `tools/call`.
 */
object RoleAccessControl {

    private val operatorWhitelist: Set<String> = setOf(
        // Descubrimiento.
        "list_sessions", "inspect_session", "search_knowledge_base", "list_macros",
        "inventory_list", "inventory_describe", "read_audit_log",
        // Lifecycle de operación.
        "start_operation", "end_operation", "current_operation",
        // Mutación (con approval gate humano y, si la op lo exige, approval token).
        "open_session", "close_session", "propose_commands", "run_macro",
        // Lectura ejecutable: whitelist estricta read-only, gate opcional (auto-approve).
        "run_readonly_command",
        // Fase 2 telemetría: ejecutan comandos de interfaces del catálogo interno y
        // devuelven JSON canónico parseado.
        "get_interface_stats", "get_link_status", "get_bandwidth_utilization",
        // Fase 3: histórico local (lectura pura de la BD).
        "get_device_history",
        // Side-channel.
        "tail_session",
        // Phase 3 Fase 4 — snapshots: el operator captura el "antes" antes de ejecutar.
        "snapshot_create", "snapshot_diff",
    )

    private val complianceWhitelist: Set<String> = setOf(
        // Solo lectura y la tool específica de evaluación.
        "list_sessions", "inspect_session", "search_knowledge_base",
        "inventory_list", "inventory_describe", "read_audit_log",
        "current_operation",
        "compliance_evaluate",
        // Phase 3 Fase 4: compliance puede leer diffs como parte de su decisión.
        "snapshot_diff",
        // Phase 3 Fase 5: compliance carga y evalúa policies; el audit masivo queda
        // para validator. policy_evaluate complementa la decisión LLM con hechos
        // determinísticos.
        "policy_load", "policy_list", "policy_evaluate",
        // Telemetría Fase 3: histórico local — lectura pura de la BD, no toca devices.
        "get_device_history",
    )

    private val validatorWhitelist: Set<String> = setOf(
        // Solo lectura.
        "list_sessions", "inspect_session", "search_knowledge_base", "list_macros",
        "inventory_list", "inventory_describe", "read_audit_log",
        "current_operation",
        // Phase 3 Fase 4: el validator es el rol que más usa snapshots.
        "snapshot_create", "snapshot_diff", "snapshot_compare_to_criteria", "rollback_propose",
        // Phase 3 Fase 5: validator audita policies sobre flotas.
        "policy_list", "policy_evaluate", "policy_audit",
        // Telemetría Fase 3: histórico local — lectura pura de la BD, no toca devices.
        "get_device_history",
    )

    fun allows(role: Role, toolName: String): Boolean = when (role) {
        Role.OPERATOR -> toolName in operatorWhitelist
        Role.COMPLIANCE -> toolName in complianceWhitelist
        Role.VALIDATOR -> toolName in validatorWhitelist
    }

    fun whitelistFor(role: Role): Set<String> = when (role) {
        Role.OPERATOR -> operatorWhitelist
        Role.COMPLIANCE -> complianceWhitelist
        Role.VALIDATOR -> validatorWhitelist
    }
}
