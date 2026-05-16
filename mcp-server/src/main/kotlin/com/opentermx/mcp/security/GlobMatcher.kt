package com.opentermx.mcp.security

/**
 * Matcher minimalista de globs estilo shell para filtrar sessionIds (`lab-*,test-?`).
 *
 * Sintaxis soportada:
 *  - `*` — cero o más caracteres.
 *  - `?` — exactamente un carácter.
 *  - `,` — separador de alternativas: el matcher pasa si alguna de las alternativas matchea.
 *
 * No soportamos `**`, ranges `[a-z]`, ni globstar — el uso esperado es identificadores
 * cortos de sesión, no paths. Si el glob es `null` o vacío, `matches` devuelve `true`
 * (sin restricción).
 */
object GlobMatcher {

    fun matches(glob: String?, sessionId: String): Boolean {
        if (glob.isNullOrBlank()) return true
        val patterns = glob.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (patterns.isEmpty()) return true
        return patterns.any { matchesSingle(it, sessionId) }
    }

    private fun matchesSingle(pattern: String, value: String): Boolean {
        val regex = patternToRegex(pattern)
        return regex.matches(value)
    }

    private fun patternToRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        for (ch in pattern) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '\\', '+', '(', ')', '[', ']', '{', '}', '|', '^', '$' ->
                    sb.append('\\').append(ch)
                else -> sb.append(ch)
            }
        }
        sb.append('$')
        return Regex(sb.toString())
    }
}