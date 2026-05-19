package com.opentermx.policy

/**
 * Renderea resultados de evaluación en JSON estructurado (un Map para pasar al wire MCP)
 * y en Markdown copy-pasteable.
 *
 * El Markdown usa headers `##` para policy + tabla por rule. La idea es que el operador
 * lo pegue tal cual en un ticket o changelog.
 */
object PolicyReportRenderer {

    fun toJson(eval: PolicyEvaluation): Map<String, Any?> = linkedMapOf(
        "policyName" to eval.policyName,
        "policyVersion" to eval.policyVersion,
        "deviceAlias" to eval.deviceAlias,
        "target" to eval.target,
        "passCount" to eval.passCount,
        "failCount" to eval.failCount,
        "warnCount" to eval.warnCount,
        "results" to eval.results.map { r ->
            linkedMapOf(
                "ruleId" to r.ruleId,
                "severity" to r.severity,
                "status" to r.status.name,
                "message" to r.message,
                "line" to r.line,
            )
        },
    )

    fun toJsonAudit(policyName: String, evaluations: List<PolicyEvaluation>): Map<String, Any?> = linkedMapOf(
        "policyName" to policyName,
        "deviceCount" to evaluations.size,
        "totalFail" to evaluations.sumOf { it.failCount },
        "totalWarn" to evaluations.sumOf { it.warnCount },
        "byDevice" to evaluations.map { e ->
            linkedMapOf(
                "deviceAlias" to e.deviceAlias,
                "passCount" to e.passCount,
                "failCount" to e.failCount,
                "warnCount" to e.warnCount,
                "results" to e.results.map { r ->
                    linkedMapOf(
                        "ruleId" to r.ruleId,
                        "severity" to r.severity,
                        "status" to r.status.name,
                        "message" to r.message,
                        "line" to r.line,
                    )
                },
            )
        },
    )

    fun toMarkdown(eval: PolicyEvaluation): String {
        val sb = StringBuilder()
        sb.append("## Policy `").append(eval.policyName).append("` v").append(eval.policyVersion).append("\n\n")
        eval.deviceAlias?.let { sb.append("**Device:** `").append(it).append("`\n\n") }
        sb.append("Resumen: ").append(eval.passCount).append(" PASS / ")
            .append(eval.failCount).append(" FAIL / ")
            .append(eval.warnCount).append(" WARN\n\n")
        sb.append("| Rule | Severity | Status | Message |\n")
        sb.append("|---|---|---|---|\n")
        for (r in eval.results) {
            sb.append("| ").append(r.ruleId)
                .append(" | ").append(r.severity)
                .append(" | ").append(r.status.name)
                .append(" | ").append(r.message.replace("|", "\\|"))
            r.line?.let { sb.append(" (línea ").append(it).append(")") }
            sb.append(" |\n")
        }
        return sb.toString()
    }

    fun toMarkdownAudit(policyName: String, evaluations: List<PolicyEvaluation>): String {
        if (evaluations.isEmpty()) {
            return "# Audit — policy `$policyName`\n\nSin devices para evaluar.\n"
        }
        val sb = StringBuilder()
        sb.append("# Audit — policy `").append(policyName).append("`\n\n")
        sb.append("Devices: ").append(evaluations.size)
            .append(" | Total FAIL: ").append(evaluations.sumOf { it.failCount })
            .append(" | Total WARN: ").append(evaluations.sumOf { it.warnCount }).append("\n\n")
        for (e in evaluations) {
            sb.append(toMarkdown(e)).append("\n")
        }
        return sb.toString()
    }
}
