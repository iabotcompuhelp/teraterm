package com.opentermx.mcp.security

import com.opentermx.ai.context.Vendor
import com.opentermx.ai.safety.RiskClassifier
import com.opentermx.ai.safety.RiskLevel

/** Veredicto del validador. [Rejected.reason] está pensado para devolverse al cliente MCP. */
sealed interface ReadOnlyValidation {
    data object Allowed : ReadOnlyValidation
    data class Rejected(val reason: String) : ReadOnlyValidation
}

/**
 * Whitelist estricta para la tool `run_readonly_command`: decide si un comando es de
 * SOLO LECTURA para el vendor dado. A diferencia del [com.opentermx.ai.safety.RiskClassifier]
 * (que es un semáforo informativo para el operador), esto es un gate ejecutable sin humano
 * en el loop, así que la política es whitelist pura — todo lo no listado se rechaza.
 *
 * Capas, en orden (todas fail-closed):
 *  1. Charset estricto: solo ASCII imprimible de un set acotado. Excluye `;`, `&`, `<`,
 *     `>`, backtick, `$`, paréntesis y todo carácter de control (incl. newlines y tabs) —
 *     mata encadenadores, substitution y redirecciones antes de cualquier parsing.
 *  2. El primer segmento (antes del primer `|`) debe empezar con un prefijo del catálogo
 *     read-only del vendor (`show`, `display`, `get`, `ping`, …).
 *  3. Cada segmento de pipe posterior debe ser un FILTRO de lectura (`include`, `match`,
 *     `count`, …). Pipes hacia comandos de escritura/exfiltración (`redirect`, `tee`,
 *     `save`, `copy`, …) no están en la lista y se rechazan.
 *  4. Defensa en profundidad: el [RiskClassifier] tiene que coincidir en SAFE; si las
 *     heurísticas anti-inyección de ahí escalan algo que esta whitelist dejó pasar, gana
 *     el rechazo.
 */
object ReadOnlyCommandValidator {

    /**
     * ASCII imprimible acotado: letras, dígitos, espacio y puntuación que aparece en
     * comandos de lectura reales (paths `flash:/x`, prefijos `10.0.0.0/24`, wildcards,
     * regex de filtros, comillas). Deliberadamente SIN `;`, `&`, `<`, `>`, `$`,
     * paréntesis ni backtick.
     */
    private val ALLOWED_CHARSET = Regex("""^[A-Za-z0-9 ._:/,\-+*?'"=@\[\]|\\^{}]+$""")

    /**
     * Primer token permitido tras un `|`: filtros de paginación/búsqueda que solo
     * transforman el output en pantalla. `redirect`, `tee`, `append`, `save`, `copy`,
     * `exec` y demás targets con efectos NO están acá a propósito.
     */
    private val READ_PIPE_FILTERS = setOf(
        // Cisco IOS/IOS-XE/NX-OS (incl. abreviaturas comunes)
        "include", "exclude", "begin", "section", "count", "i", "inc", "ex", "b",
        // JunOS
        "match", "except", "find", "last", "trim", "display", "no-more", "hold",
        // Huawei VRP / Aruba / genéricos
        "include", "exclude", "begin",
        // FortiOS
        "grep",
    )

    /**
     * Prefijos read-only por vendor. Match por frase completa al inicio del comando
     * (con boundary de palabra: `show` matchea `show version` pero no `showme`).
     * UNKNOWN usa la unión genérica de verbos de lectura — fail-closed pero usable
     * cuando el VendorDetector todavía no vio suficiente buffer.
     */
    private val GENERIC_READ_PREFIXES = setOf("show", "display", "ping", "traceroute", "tracert")

    private val VENDOR_READ_PREFIXES: Map<Vendor, Set<String>> = mapOf(
        Vendor.CISCO_IOS to setOf("show", "ping", "traceroute", "dir", "terminal length", "terminal width"),
        Vendor.CISCO_IOS_XE to setOf("show", "ping", "traceroute", "dir", "terminal length", "terminal width"),
        Vendor.CISCO_NX_OS to setOf("show", "ping", "traceroute", "dir", "terminal length", "terminal width"),
        Vendor.JUNIPER_JUNOS to setOf("show", "ping", "traceroute"),
        Vendor.HUAWEI_VRP to setOf("display", "ping", "tracert", "dir"),
        Vendor.MIKROTIK_ROUTEROS to setOf("ping"),
        Vendor.ARUBA_OS to setOf("show", "ping", "traceroute"),
        Vendor.FORTINET_FORTIOS to setOf("get", "show", "execute ping", "execute traceroute"),
        Vendor.UNKNOWN to GENERIC_READ_PREFIXES,
    )

    /** RouterOS no tiene `show`: la lectura canónica es `/path ... print [detail|count-only|where …]`. */
    private val MIKROTIK_PRINT = Regex("""^/[a-z][a-z0-9 /\-]*\bprint(\s.*)?$""")

    fun validate(rawCommand: String, vendor: Vendor): ReadOnlyValidation {
        val command = rawCommand.trim()
        if (command.isEmpty()) return Rejected("comando vacío")

        if (command.any { it.code < 0x20 || it.code == 0x7f }) {
            return Rejected("caracteres de control embebidos (posible inyección multilínea)")
        }
        if (!ALLOWED_CHARSET.matches(command)) {
            return Rejected(
                "caracteres fuera del set permitido para comandos read-only " +
                    "(`;`, `&`, `<`, `>`, backtick, `$`, paréntesis y control chars están prohibidos)"
            )
        }

        val segments = command.split('|').map { it.trim() }
        if (segments.any { it.isEmpty() }) {
            return Rejected("pipe vacío o encadenador `||` — no permitido en comandos read-only")
        }

        val head = segments.first().lowercase()
        val prefixes = VENDOR_READ_PREFIXES[vendor] ?: GENERIC_READ_PREFIXES
        val headAllowed = prefixes.any { head == it || head.startsWith("$it ") } ||
            (vendor == Vendor.MIKROTIK_ROUTEROS && MIKROTIK_PRINT.matches(head))
        if (!headAllowed) {
            return Rejected(
                "`${segments.first()}` no está en la whitelist read-only de ${vendor.displayName} " +
                    "(${prefixes.sorted().joinToString(", ")}). Para comandos mutativos usá `propose_commands`."
            )
        }

        for (filter in segments.drop(1)) {
            val firstToken = filter.split(Regex("\\s+")).first().lowercase()
            if (firstToken !in READ_PIPE_FILTERS) {
                return Rejected(
                    "pipe hacia `$firstToken` no permitido: solo filtros de lectura " +
                        "(include/exclude/begin/section/match/count/…). Nada de redirect/tee/save/copy."
                )
            }
        }

        val risk = RiskClassifier.classifyLine(rawCommand, vendor).risk
        if (risk != RiskLevel.SAFE) {
            return Rejected("el clasificador de riesgo marcó el comando como $risk — no es read-only")
        }
        return ReadOnlyValidation.Allowed
    }

    private fun Rejected(reason: String) = ReadOnlyValidation.Rejected(reason)
}

