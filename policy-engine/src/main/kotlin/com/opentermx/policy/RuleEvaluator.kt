package com.opentermx.policy

/**
 * Evalúa una lista de [Rule] contra un snapshot. Implementación 100% determinística;
 * misma config + mismas reglas → mismo output bit-a-bit. Sin LLM involucrado.
 *
 * Tipos soportados (mirror simplificado de clanet):
 *
 *  - `pattern_deny`: la línea **no debe** matchear el regex.
 *    PASS si ninguna línea matchea; FAIL en la primera línea matcheada.
 *  - `require`: alguna línea **debe** matchear el regex.
 *    PASS si al menos una matchea; FAIL si ninguna.
 *  - `recommend`: idéntico a `require` pero la falla se reporta como WARN (no FAIL).
 *
 * Tipo desconocido → status WARN con mensaje claro.
 */
object RuleEvaluator {

    fun evaluate(
        policy: Policy,
        snapshotContent: String,
        deviceAlias: String?,
        parser: DeviceConfigParser = DeviceConfigParser.Default,
    ): PolicyEvaluation {
        val lines = parser.lines(snapshotContent)
        val results = policy.rules.map { evaluateOne(it, lines) }
        return PolicyEvaluation(
            policyName = policy.policy.name,
            policyVersion = policy.policy.version,
            deviceAlias = deviceAlias,
            target = policy.rules.firstOrNull()?.target ?: "running_config",
            results = results,
        )
    }

    private fun evaluateOne(rule: Rule, lines: List<String>): RuleResult {
        val regex = runCatching { Regex(rule.pattern) }.getOrNull()
            ?: return RuleResult(
                ruleId = rule.id, severity = rule.severity,
                status = RuleStatus.WARN,
                message = "Regex inválida en rule `${rule.id}`: `${rule.pattern}`",
            )
        return when (rule.type) {
            "pattern_deny" -> evalPatternDeny(rule, lines, regex)
            "require" -> evalRequire(rule, lines, regex, failureStatus = RuleStatus.FAIL)
            "recommend" -> evalRequire(rule, lines, regex, failureStatus = RuleStatus.WARN)
            else -> RuleResult(
                ruleId = rule.id, severity = rule.severity,
                status = RuleStatus.WARN,
                message = "Tipo de rule desconocido `${rule.type}` — saltado",
            )
        }
    }

    private fun evalPatternDeny(rule: Rule, lines: List<String>, regex: Regex): RuleResult {
        for ((idx, line) in lines.withIndex()) {
            if (regex.containsMatchIn(line)) {
                return RuleResult(
                    ruleId = rule.id, severity = rule.severity,
                    status = RuleStatus.FAIL,
                    message = rule.message,
                    line = idx + 1,
                )
            }
        }
        return RuleResult(
            ruleId = rule.id, severity = rule.severity,
            status = RuleStatus.PASS,
            message = "OK — patrón prohibido no encontrado",
        )
    }

    private fun evalRequire(
        rule: Rule, lines: List<String>, regex: Regex, failureStatus: RuleStatus,
    ): RuleResult {
        for ((idx, line) in lines.withIndex()) {
            if (regex.containsMatchIn(line)) {
                return RuleResult(
                    ruleId = rule.id, severity = rule.severity,
                    status = RuleStatus.PASS,
                    message = "OK — patrón requerido encontrado",
                    line = idx + 1,
                )
            }
        }
        return RuleResult(
            ruleId = rule.id, severity = rule.severity,
            status = failureStatus,
            message = rule.message,
        )
    }
}
