package com.opentermx.mcp.operation

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Intención estructurada que un cliente LLM declara al iniciar una operación. Schema
 * formal en `resources/schemas/operation-context.schema.json`. La validación la hace
 * [OperationContextValidator]; esta data class es solo la forma deserializada.
 *
 * Convenciones:
 *  - El YAML usa `snake_case`; Jackson mapea con `@JsonProperty`.
 *  - Listas opcionales se materializan como `emptyList()` para que las consultas
 *    `scope.forbiddenCommands.any { … }` no requieran null-check.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class OperationContext(
    val operation: OperationMeta,
    val scope: OperationScope = OperationScope(),
    @JsonProperty("success_criteria")
    val successCriteria: List<SuccessCriterion> = emptyList(),
    val constraints: OperationConstraints = OperationConstraints(),
)

@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class OperationMeta(
    /** Si viene null al cargar, [OperationRegistry.start] genera uno. */
    val id: String? = null,
    val description: String,
    @JsonProperty("initiated_by") val initiatedBy: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class OperationScope(
    val devices: List<String> = emptyList(),
    @JsonProperty("allowed_commands_prefix")
    val allowedCommandsPrefix: List<String> = emptyList(),
    @JsonProperty("forbidden_commands")
    val forbiddenCommands: List<String> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SuccessCriterion(
    val type: String,
    val command: String? = null,
    val pattern: String? = null,
    @JsonProperty("interface_name") val interfaceName: String? = null,
    val destination: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class OperationConstraints(
    @JsonProperty("max_duration_minutes") val maxDurationMinutes: Int? = null,
    @JsonProperty("require_compliance_approval") val requireComplianceApproval: Boolean = false,
    @JsonProperty("require_snapshot") val requireSnapshot: Boolean = false,
)

/**
 * Resultado de [OperationScope.validateCommand]: o pasa, o se rechaza con razón concreta
 * lista para devolverle al LLM cliente.
 */
sealed interface CommandValidation {
    data object Allowed : CommandValidation
    data class Rejected(val reason: String) : CommandValidation
}

/**
 * Aplica los filtros declarados en el scope a una línea de comando individual.
 * Política:
 *  - `forbiddenCommands`: substring match case-insensitive. Si matchea cualquiera, rechazo.
 *  - `allowedCommandsPrefix`: si la lista NO está vacía, el comando debe empezar con alguno
 *    (trim+lowercase de ambos). Si la lista está vacía, no se chequea (todo pasa).
 */
fun OperationScope.validateCommand(command: String): CommandValidation {
    val trimmed = command.trim()
    if (trimmed.isEmpty()) return CommandValidation.Allowed
    val lower = trimmed.lowercase()

    val forbiddenHit = forbiddenCommands.firstOrNull { needle ->
        lower.contains(needle.trim().lowercase())
    }
    if (forbiddenHit != null) {
        return CommandValidation.Rejected(
            "Comando bloqueado por scope.forbidden_commands (matchea \"$forbiddenHit\"): $command"
        )
    }

    if (allowedCommandsPrefix.isNotEmpty()) {
        val matchesPrefix = allowedCommandsPrefix.any { prefix ->
            lower.startsWith(prefix.trim().lowercase())
        }
        if (!matchesPrefix) {
            return CommandValidation.Rejected(
                "Comando fuera de scope.allowed_commands_prefix (esperado prefijo en " +
                    "${allowedCommandsPrefix.joinToString(", ", "[", "]")}): $command"
            )
        }
    }

    return CommandValidation.Allowed
}
