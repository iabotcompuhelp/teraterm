package com.opentermx.mcp.snapshots

import com.opentermx.mcp.operation.SuccessCriterion

/**
 * Phase 3 Fase 4 — evalúa los `success_criteria` declarados en el Operation Context
 * contra un snapshot post-cambio.
 *
 * Tipos soportados ahora:
 *  - `command_output_contains` — el `content` del snapshot debe contener `pattern`
 *    (regex, case-insensitive default). Si el criterio declara `command`, el matcheo
 *    arranca después de la primera línea que contenga ese comando — útil cuando el
 *    snapshot incluye la sesión completa.
 *  - `no_interface_down` — heurística: ninguna línea matchea
 *    `[Ii]nterface.*\\s+(?:is\\s+)?down|state\\s+down|administratively\\s+down`.
 *  - `route_exists` — busca `destination` (literal) en el snapshot.
 *
 * Extensible: si llega un `type` desconocido, lo reporta como WARN sin invalidar el set
 * completo de criterios — el LLM ve el detalle y decide.
 */
object SuccessCriteriaEvaluator {

    data class EvaluationResult(
        val results: List<CriterionResult>,
        val overall: Overall,
    ) {
        val summary: String get() = "${results.count { it.status == Status.PASS }}/${results.size} criterios PASS"
    }

    data class CriterionResult(
        val type: String,
        val status: Status,
        val message: String,
        val criterion: SuccessCriterion,
    )

    enum class Status { PASS, FAIL, WARN }
    enum class Overall { ALL_PASS, PARTIAL, ALL_FAIL }

    fun evaluate(snapshot: Snapshot, criteria: List<SuccessCriterion>): EvaluationResult {
        if (criteria.isEmpty()) {
            return EvaluationResult(emptyList(), Overall.ALL_PASS)
        }
        val results = criteria.map { evaluateOne(snapshot, it) }
        val passCount = results.count { it.status == Status.PASS }
        val overall = when (passCount) {
            results.size -> Overall.ALL_PASS
            0 -> Overall.ALL_FAIL
            else -> Overall.PARTIAL
        }
        return EvaluationResult(results, overall)
    }

    private fun evaluateOne(snapshot: Snapshot, criterion: SuccessCriterion): CriterionResult {
        return when (criterion.type) {
            "command_output_contains" -> evalCommandOutputContains(snapshot, criterion)
            "no_interface_down" -> evalNoInterfaceDown(snapshot, criterion)
            "route_exists" -> evalRouteExists(snapshot, criterion)
            else -> CriterionResult(
                type = criterion.type, status = Status.WARN,
                message = "Tipo de criterio desconocido `${criterion.type}` — saltado",
                criterion = criterion,
            )
        }
    }

    private fun evalCommandOutputContains(snapshot: Snapshot, criterion: SuccessCriterion): CriterionResult {
        val pattern = criterion.pattern
            ?: return CriterionResult(criterion.type, Status.WARN,
                "Falta `pattern` en el criterio", criterion)
        val haystack = if (criterion.command.isNullOrBlank()) {
            snapshot.content
        } else {
            // Si declara `command`, recortamos a partir de la primera línea que lo contiene.
            val lines = snapshot.content.lines()
            val idx = lines.indexOfFirst { it.contains(criterion.command, ignoreCase = true) }
            if (idx < 0) {
                return CriterionResult(criterion.type, Status.FAIL,
                    "El snapshot no contiene rastro del comando `${criterion.command}`", criterion)
            }
            lines.drop(idx).joinToString("\n")
        }
        val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
            ?: return CriterionResult(criterion.type, Status.WARN,
                "Pattern `$pattern` no es regex válida", criterion)
        val match = regex.containsMatchIn(haystack)
        return CriterionResult(
            type = criterion.type,
            status = if (match) Status.PASS else Status.FAIL,
            message = if (match) "Encontrado: $pattern" else "No matchea: $pattern",
            criterion = criterion,
        )
    }

    private val DOWN_REGEX = Regex(
        """[Ii]nterface[^\n]*?\s+(?:is\s+)?(?:administratively\s+)?down|state\s+(?:administratively\s+)?down|line\s+protocol\s+is\s+down""",
        RegexOption.IGNORE_CASE,
    )

    private fun evalNoInterfaceDown(snapshot: Snapshot, criterion: SuccessCriterion): CriterionResult {
        val hits = snapshot.content.lines().filter { DOWN_REGEX.containsMatchIn(it) }
        return if (hits.isEmpty()) {
            CriterionResult(criterion.type, Status.PASS,
                "Ninguna interfaz down detectada", criterion)
        } else {
            CriterionResult(criterion.type, Status.FAIL,
                "Interfaces down (${hits.size}): ${hits.take(3).joinToString(" | ")}",
                criterion)
        }
    }

    private fun evalRouteExists(snapshot: Snapshot, criterion: SuccessCriterion): CriterionResult {
        val dest = criterion.destination
            ?: return CriterionResult(criterion.type, Status.WARN,
                "Falta `destination` en el criterio", criterion)
        val found = snapshot.content.lineSequence().any { it.contains(dest) }
        return CriterionResult(
            type = criterion.type,
            status = if (found) Status.PASS else Status.FAIL,
            message = if (found) "Route `$dest` presente" else "Route `$dest` no encontrada",
            criterion = criterion,
        )
    }
}
