package com.opentermx.policy

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Phase 3 Fase 5 — Policy engine determinístico.
 *
 * Una policy es un set de reglas que se evalúa contra un snapshot de un device. Las
 * reglas son regex-based por defecto; un `DeviceConfigParser` registrado por device_type
 * puede transformar el snapshot antes de evaluar (hook para parseo estructurado futuro).
 *
 * NO hay LLM involucrado: misma policy + misma config → mismo resultado, byte-a-byte.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Policy(
    val policy: PolicyMeta,
    val rules: List<Rule>,
)

@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PolicyMeta(
    val name: String,
    val version: String,
    @JsonProperty("applies_to") val appliesTo: PolicyAppliesTo? = null,
)

@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PolicyAppliesTo(
    @JsonProperty("device_types") val deviceTypes: List<String> = emptyList(),
    @JsonProperty("tags_any") val tagsAny: List<String> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Rule(
    val id: String,
    val severity: String,
    /** `pattern_deny | require | recommend`. Tipos desconocidos producen status=WARN. */
    val type: String,
    /** `running_config` por ahora — futuro: `routing_table`, etc. */
    val target: String = "running_config",
    val pattern: String,
    val message: String,
)

enum class RuleStatus { PASS, FAIL, WARN }

data class RuleResult(
    val ruleId: String,
    val severity: String,
    val status: RuleStatus,
    val message: String,
    /** Línea del snapshot donde matcheó / faltó. null si no aplica. */
    val line: Int? = null,
)

data class PolicyEvaluation(
    val policyName: String,
    val policyVersion: String,
    val deviceAlias: String?,
    val target: String,
    val results: List<RuleResult>,
) {
    val passCount: Int get() = results.count { it.status == RuleStatus.PASS }
    val failCount: Int get() = results.count { it.status == RuleStatus.FAIL }
    val warnCount: Int get() = results.count { it.status == RuleStatus.WARN }
}
