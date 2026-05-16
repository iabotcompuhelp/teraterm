package com.opentermx.mcp.security

import com.opentermx.ai.safety.CredentialRedactor
import com.opentermx.ai.safety.RedactionRule

/**
 * Construye un [CredentialRedactor] combinando las reglas built-in con las custom que el
 * operador haya configurado en settings (`mcpServerCustomRedactionRules`). Vive en
 * `mcp-server/security/` (no en `ai-assistant`) porque las reglas custom son una decisión
 * operacional del despliegue MCP, no del módulo IA en general.
 */
object RedactorFactory {

    fun fromCustomRules(customRules: List<Pair<String, String>>): CredentialRedactor {
        if (customRules.isEmpty()) return CredentialRedactor()
        val compiled = customRules.mapNotNull { (pattern, replacement) ->
            runCatching {
                RedactionRule(
                    pattern = Regex(pattern),
                    replacement = replacement,
                    description = "custom",
                )
            }.getOrNull()
        }
        // Built-in primero (más generales y bien probadas), custom después.
        return CredentialRedactor(CredentialRedactor.BUILT_IN_RULES + compiled)
    }
}