package com.opentermx.mcp.fingerprint

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.opentermx.fingerprint.DeviceIdentity
import com.opentermx.netparsers.Vendor
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory

/**
 * Heurística DECLARATIVA de rol de dispositivo (subfase 5A): reglas ordenadas en
 * `profiles/role-rules.yaml` (embebido + override del operador), primera que matchea
 * gana. Mismo patrón carga/fallback que la whitelist read-only de la Fase 1.
 *
 * El resultado es una SUGERENCIA (error #36): el rol informa, no autoriza, y el valor
 * confirmado por el operador (roleSource=OPERATOR, Fase 5B) pisa siempre al inferido.
 */
class RoleRules(private val rules: List<Rule>) {

    data class Rule(
        val role: String,
        val vendor: Vendor?,
        val modelPattern: Regex?,
    ) {
        fun matches(identity: DeviceIdentity): Boolean {
            if (vendor != null && identity.vendor != vendor) return false
            if (modelPattern != null) {
                val model = identity.model ?: return false
                if (!modelPattern.containsMatchIn(model)) return false
            }
            return true
        }

        /** Qué condición decidió (para logs y el campo roleMatchedBy del reporte). */
        val matchedBy: String
            get() = when {
                vendor != null && modelPattern != null -> "vendor+modelPattern"
                vendor != null -> "vendor"
                modelPattern != null -> "modelPattern"
                else -> "fallback"
            }
    }

    data class Inference(val role: String, val matchedBy: String)

    fun infer(identity: DeviceIdentity): Inference {
        val rule = rules.firstOrNull { it.matches(identity) }
            ?: return Inference(FALLBACK_ROLE, "fallback")
        return Inference(rule.role, rule.matchedBy)
    }

    companion object {
        const val FALLBACK_ROLE = "unknown"
        const val EMBEDDED_RESOURCE = "/profiles/role-rules.yaml"
        const val SUPPORTED_VERSION = 1

        val USER_OVERRIDE_PATH: Path =
            Path.of(System.getProperty("user.home"), ".opentermx", "profiles", "role-rules.yaml")

        private val log = LoggerFactory.getLogger(RoleRules::class.java)
        private val yamlMapper: ObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

        /** Override del operador si existe y parsea; si no, el embebido. Nunca lanza. */
        fun default(): RoleRules {
            if (Files.isRegularFile(USER_OVERRIDE_PATH)) {
                runCatching { fromYaml(Files.readString(USER_OVERRIDE_PATH)) }
                    .onSuccess {
                        log.info("Reglas de rol cargadas del override {}", USER_OVERRIDE_PATH)
                        return it
                    }
                    .onFailure { e ->
                        log.error(
                            "Override {} inválido ({}). Usando reglas embebidas.",
                            USER_OVERRIDE_PATH, e.message,
                        )
                    }
            }
            return embedded()
        }

        fun embedded(): RoleRules {
            val text = RoleRules::class.java.getResourceAsStream(EMBEDDED_RESOURCE)
                ?.bufferedReader()?.use { it.readText() }
                ?: error("Recurso $EMBEDDED_RESOURCE ausente del classpath")
            return fromYaml(text)
        }

        /** Lanza [IllegalArgumentException] ante shape/regex/versión inválidos. */
        fun fromYaml(yamlText: String): RoleRules {
            val root = yamlMapper.readValue(yamlText, RulesFile::class.java)
            require(root.version == SUPPORTED_VERSION) {
                "role-rules.yaml versión ${root.version} no soportada (esperaba $SUPPORTED_VERSION)"
            }
            require(root.rules.isNotEmpty()) { "role-rules.yaml sin reglas" }
            return RoleRules(
                root.rules.map { entry ->
                    require(entry.role.isNotBlank()) { "regla sin `role`" }
                    Rule(
                        role = entry.role,
                        vendor = entry.condition.vendor?.let { name ->
                            runCatching { Vendor.valueOf(name) }.getOrElse {
                                throw IllegalArgumentException("vendor desconocido en role-rules: `$name`")
                            }
                        },
                        modelPattern = entry.condition.modelPattern?.let { Regex(it) },
                    )
                }
            )
        }
    }

    private data class RulesFile(
        val version: Int = 1,
        val rules: List<RuleEntry> = emptyList(),
    )

    private data class RuleEntry(
        val role: String = "",
        @JsonProperty("when")
        val condition: WhenClause = WhenClause(),
    )

    private data class WhenClause(
        val vendor: String? = null,
        val modelPattern: String? = null,
    )
}
