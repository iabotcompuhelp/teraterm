package com.opentermx.ai.safety

import com.opentermx.ai.context.Vendor

/**
 * Clasifica cada línea de un bloque de comandos generado por IA en SAFE / CONFIG / DANGEROUS.
 * El catálogo depende del vendor; el patrón es: si la primera palabra (o el inicio de la línea)
 * coincide con un keyword DANGEROUS gana, si no, con CONFIG, si no, SAFE por defecto.
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

    fun classify(commands: List<String>, vendor: Vendor): List<ClassifiedCommand> =
        commands.map { classifyLine(it, vendor) }

    fun classifyLine(rawLine: String, vendor: Vendor): ClassifiedCommand {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("#")) {
            return ClassifiedCommand(rawLine, RiskLevel.SAFE)
        }
        val firstToken = trimmed.split(Regex("\\s+")).first().lowercase()
        val lower = trimmed.lowercase()

        // Vendor-specific dangerous overrides (whole-phrase match)
        VENDOR_DANGEROUS[vendor]?.let { phrases ->
            if (phrases.any { lower.startsWith(it.lowercase()) }) {
                return ClassifiedCommand(rawLine, RiskLevel.DANGEROUS)
            }
        }

        // Generic dangerous tokens / patterns
        if (firstToken in DANGEROUS_TOKENS_GENERIC) return ClassifiedCommand(rawLine, RiskLevel.DANGEROUS)
        if (DANGEROUS_PATTERNS_GENERIC.any { it.containsMatchIn(lower) }) {
            return ClassifiedCommand(rawLine, RiskLevel.DANGEROUS)
        }

        // Safe / Config
        if (firstToken in SAFE_TOKENS_GENERIC) return ClassifiedCommand(rawLine, RiskLevel.SAFE)
        if (firstToken in CONFIG_TOKENS_GENERIC) return ClassifiedCommand(rawLine, RiskLevel.CONFIG)

        // Fallback: indented config-block lines (interface params, etc.)
        if (rawLine.startsWith(" ") || rawLine.startsWith("\t")) {
            return ClassifiedCommand(rawLine, RiskLevel.CONFIG)
        }
        return ClassifiedCommand(rawLine, RiskLevel.SAFE)
    }
}
