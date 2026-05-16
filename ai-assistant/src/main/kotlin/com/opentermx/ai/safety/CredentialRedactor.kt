package com.opentermx.ai.safety

import com.opentermx.ai.context.Vendor

/**
 * Una regla de redacción: un [pattern] regex, un [replacement] que típicamente preserva
 * el contexto reemplazando el secreto por `********`, y un [vendor] opcional que limita
 * la aplicabilidad de la regla cuando se conoce el vendor de la sesión.
 *
 * Cuando [vendor] es `null`, la regla aplica a cualquier sesión.
 */
data class RedactionRule(
    val pattern: Regex,
    val replacement: String,
    val vendor: Vendor? = null,
    val description: String = "",
)

/**
 * Pipeline de redacción aplicada a cualquier output que vuelva al cliente MCP — buffers
 * de sesión, output tail de comandos ejecutados, entradas del audit log — antes de cruzar
 * el wire. Razón de existir: un LLM externo NO debe ver credenciales en claro que
 * accidentalmente aparezcan en el output del terminal (un `show running-config` típico
 * trae `enable secret` y community strings).
 *
 * Las reglas built-in cubren los vectores comunes en routers/switches; el operador puede
 * agregar reglas custom desde la UI (`mcpServerCustomRedactionRules`).
 *
 * Diseño: una sola pasada por cada regla aplicable. Las reglas son inmutables y reusables;
 * `CredentialRedactor` es stateless y safe para llamar desde múltiples threads.
 */
class CredentialRedactor(
    private val rules: List<RedactionRule> = BUILT_IN_RULES,
) {

    /**
     * Aplica todas las reglas aplicables al [vendor] al texto [input] y devuelve el
     * resultado redactado. Si [vendor] es `null` se aplican solo las reglas globales
     * (las que tienen `vendor == null`).
     */
    fun redact(input: String, vendor: Vendor? = null): String {
        if (input.isEmpty()) return input
        var result = input
        for (rule in rules) {
            if (rule.vendor != null && rule.vendor != vendor) continue
            result = rule.pattern.replace(result, rule.replacement)
        }
        return result
    }

    /** Atajo para redactar líneas individuales preservando estructura de lista. */
    fun redactLines(lines: List<String>, vendor: Vendor? = null): List<String> =
        if (lines.isEmpty()) lines else lines.map { redact(it, vendor) }

    companion object {

        /**
         * Reglas built-in. Las patterns priorizan **conservar la palabra-clave para que el
         * cliente MCP entienda qué tipo de credencial había** y solo reemplazar el secreto
         * por `********`. Tests cubren cada regla con un caso positivo y uno negativo.
         */
        val BUILT_IN_RULES: List<RedactionRule> = listOf(
            // 1) Cisco/Arista enable secret / password / secret
            RedactionRule(
                pattern = Regex("""(?i)\b(enable\s+(?:secret|password)|password|secret)(\s+\d)?\s+\S+"""),
                replacement = "$1$2 ********",
                description = "Cisco/Arista enable secret / password",
            ),
            // 2) SNMP community strings
            RedactionRule(
                pattern = Regex("""(?i)\bsnmp-server\s+community\s+\S+"""),
                replacement = "snmp-server community ********",
                description = "SNMP community",
            ),
            // 3) TACACS / RADIUS keys
            RedactionRule(
                pattern = Regex("""(?i)\b(tacacs-server\s+key|radius-server\s+key|key\s+\d+)\s+\S+"""),
                replacement = "$1 ********",
                description = "TACACS/RADIUS key",
            ),
            // 4) Authorization bearer headers
            RedactionRule(
                pattern = Regex("""(?i)\bBearer\s+[A-Za-z0-9._\-]+"""),
                replacement = "Bearer ********",
                description = "HTTP Bearer token",
            ),
            // 5) Llaves privadas en bloques PEM (multiline)
            RedactionRule(
                pattern = Regex(
                    """-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----""",
                ),
                replacement = "[redacted-private-key]",
                description = "PEM private key block",
            ),
        )
    }
}