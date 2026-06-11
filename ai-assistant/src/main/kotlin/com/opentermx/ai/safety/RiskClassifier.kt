package com.opentermx.ai.safety

import com.opentermx.ai.context.Vendor

/**
 * Clasifica cada línea de un bloque de comandos generado por IA en SAFE / CONFIG / DANGEROUS.
 * El catálogo depende del vendor; el patrón es: si la primera palabra (o el inicio de la línea)
 * coincide con un keyword DANGEROUS gana, si no, con CONFIG, si no, SAFE por defecto.
 *
 * Anti-inyección (revisión 2026-06): clasificar sólo el primer token dejaba pasar payloads
 * como `show version\nreload` (newline embebido) o `show run | redirect tftp://...`
 * (exfiltración vía pipe). Reglas de escalado, ANTES del catálogo por token:
 *  - caracteres de control (fuera de tab) → DANGEROUS;
 *  - command substitution (backtick, `$( )`) → DANGEROUS;
 *  - pipe hacia targets de exfiltración/ejecución (`redirect`, `tee`, `nc`, `curl`, …) →
 *    DANGEROUS — los filtros legítimos (`| include`, `| match`, …) NO escalan;
 *  - redirección tipo shell (` > `, ` >> `) → DANGEROUS;
 *  - líneas con saltos embebidos o encadenadores (`;`, `&&`, `||`) se parten y la línea
 *    completa hereda el riesgo del PEOR segmento.
 * Sesgo deliberado: un falso positivo cuesta una aprobación manual de más; un falso
 * negativo ejecuta un comando destructivo auto-aprobado.
 */
object RiskClassifier {

    private val DANGEROUS_TOKENS_GENERIC = setOf(
        "erase", "delete", "format", "reload", "reset", "destroy",
        "wipe", "factory-reset", "factory-default", "remove",
        // `shutdown` solo (sin "no" delante) deja la interfaz down — peligroso por riesgo
        // de pérdida de conectividad. "no shutdown" cae bajo firstToken="no" → CONFIG.
        "shutdown",
    )

    private val DANGEROUS_PATTERNS_GENERIC = listOf(
        Regex("""\bwrite\s+erase\b""", RegexOption.IGNORE_CASE),
        Regex("""\bno\s+ip\s+address\b""", RegexOption.IGNORE_CASE),
    )

    private val SAFE_TOKENS_GENERIC = setOf(
        "show", "display", "get", "ping", "traceroute", "tracert", "monitor", "debug-show"
    )

    private val CONFIG_TOKENS_GENERIC = setOf(
        "configure", "interface", "vlan", "router", "set", "system-view", "config",
        "ip", "ipv6", "hostname", "snmp-server", "username", "no", "exit", "end",
        "switchport", "channel-group", "spanning-tree", "description", "feature",
        // `write memory`, `copy running-config startup-config`: guardan configuración (CONFIG)
        "write", "copy",
    )

    // Variaciones específicas por vendor para reducir falsos positivos
    private val VENDOR_DANGEROUS: Map<Vendor, Set<String>> = mapOf(
        Vendor.MIKROTIK_ROUTEROS to setOf("/system reset-configuration", "/file remove"),
        Vendor.JUNIPER_JUNOS to setOf("delete", "rollback 0", "request system zeroize"),
        Vendor.HUAWEI_VRP to setOf("reset saved-configuration", "factory-configuration"),
    )

    /**
     * Targets de pipe que sacan el output del device o ejecutan algo con él. Los filtros
     * de lectura (`include`, `exclude`, `begin`, `section`, `match`, `count`, `display`,
     * `no-more`, …) NO están acá a propósito: `show run | include ntp` es uso legítimo.
     */
    private val DANGEROUS_PIPE_TARGETS = Regex(
        """\|\s*(redirect|tee|append|save|copy|exec|nc|netcat|curl|wget|tftp|ftp|scp|bash|sh|ksh|zsh|python\d*|perl|ruby|xargs)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** ` > ` / ` >> ` delimitados por espacios: redirección estilo shell hacia un archivo. */
    private val SHELL_REDIRECT = Regex("""\s>>?\s""")

    /** Backtick o `$( ` — command substitution en shells embebidos (Arista bash, Linux). */
    private val COMMAND_SUBSTITUTION = Regex("""`|\$\(""")

    /** Encadenadores de comandos: el riesgo de la línea es el del peor segmento. */
    private val CHAIN_SEPARATORS = Regex(""";|&&|\|\|""")

    fun classify(commands: List<String>, vendor: Vendor): List<ClassifiedCommand> =
        commands.map { classifyLine(it, vendor) }

    fun classifyLine(rawLine: String, vendor: Vendor): ClassifiedCommand {
        // Inyección multilínea: "show version\nreload" llega como UNA línea al
        // clasificador pero el device la ejecuta como dos. Se clasifica cada renglón
        // embebido y la línea entera hereda el peor riesgo.
        if (rawLine.contains('\n') || rawLine.contains('\r')) {
            val worst = rawLine.split('\n', '\r')
                .filter { it.isNotBlank() }
                .maxOfOrNull { classifySegment(it, vendor) } ?: RiskLevel.SAFE
            return ClassifiedCommand(rawLine, worst)
        }
        return ClassifiedCommand(rawLine, classifySegment(rawLine, vendor))
    }

    private fun classifySegment(rawLine: String, vendor: Vendor): RiskLevel {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("#")) {
            return RiskLevel.SAFE
        }

        // Escalado anti-inyección — antes del catálogo por token, para que un prefijo
        // benigno ("show …") no blanquee el resto de la línea.
        if (trimmed.any { it.code < 0x20 && it != '\t' }) return RiskLevel.DANGEROUS
        if (COMMAND_SUBSTITUTION.containsMatchIn(trimmed)) return RiskLevel.DANGEROUS
        if (DANGEROUS_PIPE_TARGETS.containsMatchIn(trimmed)) return RiskLevel.DANGEROUS
        if (SHELL_REDIRECT.containsMatchIn(trimmed)) return RiskLevel.DANGEROUS

        // Encadenadores: clasificar cada segmento por separado y devolver el peor.
        if (CHAIN_SEPARATORS.containsMatchIn(trimmed)) {
            return trimmed.split(CHAIN_SEPARATORS)
                .filter { it.isNotBlank() }
                .maxOfOrNull { classifyTokenCatalog(it.trim(), indented = false, vendor) }
                ?: RiskLevel.SAFE
        }

        return classifyTokenCatalog(
            trimmed,
            indented = rawLine.startsWith(" ") || rawLine.startsWith("\t"),
            vendor = vendor,
        )
    }

    /** El catálogo por token original: vendor overrides → dangerous → safe → config → fallback. */
    private fun classifyTokenCatalog(trimmed: String, indented: Boolean, vendor: Vendor): RiskLevel {
        val firstToken = trimmed.split(Regex("\\s+")).first().lowercase()
        val lower = trimmed.lowercase()

        // Vendor-specific dangerous overrides (whole-phrase match)
        VENDOR_DANGEROUS[vendor]?.let { phrases ->
            if (phrases.any { lower.startsWith(it.lowercase()) }) {
                return RiskLevel.DANGEROUS
            }
        }

        // Generic dangerous tokens / patterns
        if (firstToken in DANGEROUS_TOKENS_GENERIC) return RiskLevel.DANGEROUS
        if (DANGEROUS_PATTERNS_GENERIC.any { it.containsMatchIn(lower) }) {
            return RiskLevel.DANGEROUS
        }

        // Safe / Config
        if (firstToken in SAFE_TOKENS_GENERIC) return RiskLevel.SAFE
        if (firstToken in CONFIG_TOKENS_GENERIC) return RiskLevel.CONFIG

        // Fallback: indented config-block lines (interface params, etc.)
        if (indented) return RiskLevel.CONFIG
        return RiskLevel.SAFE
    }
}
