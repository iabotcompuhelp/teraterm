package com.opentermx.mcp.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.opentermx.ai.context.Vendor
import com.opentermx.ai.safety.RiskClassifier
import com.opentermx.ai.safety.RiskLevel
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory

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
 * Fase 1 del plan de telemetría: la whitelist son REGEX POR VENDOR cargadas de
 * `policies/readonly-commands.yaml` (recurso embebido), con override del operador en
 * `~/.opentermx/policies/readonly-commands.yaml`. Si el override existe pero no parsea,
 * se loguea error y se cae al embebido (fail-closed hacia lo conocido, no hacia "permitir").
 *
 * Capas, en orden (todas fail-closed):
 *  1. Longitud: 2..512 caracteres.
 *  2. Charset estricto: solo ASCII imprimible acotado. Excluye `;`, `&`, `<`, `>`,
 *     backtick, `$`, paréntesis y todo carácter de control (incl. newlines y tabs) —
 *     mata encadenadores, substitution y redirecciones antes de cualquier parsing.
 *  3. El primer segmento (antes del primer pipe) debe matchear una regex de la
 *     whitelist del vendor. Vendor `UNKNOWN` => rechazo (sin whitelist no se adivina).
 *  4. `deny` del YAML gana sobre la whitelist (p. ej. `show tech-support`,
 *     `diagnose sys kill`).
 *  5. Cada segmento de pipe posterior debe ser un FILTRO de lectura (`include`,
 *     `match`, `count`, …). Pipes hacia comandos de escritura/exfiltración
 *     (`redirect`, `tee`, `save`, `copy`, …) no están en la lista y se rechazan.
 *  6. Defensa en profundidad: el [RiskClassifier] tiene que coincidir en SAFE.
 */
class ReadOnlyCommandValidator(
    private val whitelist: Map<Vendor, List<Regex>>,
    private val deny: List<Regex>,
    /**
     * Fase 6A (schema v2): whitelists con NOMBRE, referenciadas desde el catálogo de
     * modelos vía `metadata.readonlyProfile`. Un perfil es más específico que el vendor
     * (p. ej. `arubaos_switch` vs `aruba_aoscx`) — cuando el caller lo pasa y existe,
     * SU `allow` reemplaza al del vendor y su `deny` se suma al global.
     */
    private val profiles: Map<String, Profile> = emptyMap(),
) {

    data class Profile(val allow: List<Regex>, val deny: List<Regex>)

    /** Nombres de perfiles cargados (diagnóstico y validación de packs del catálogo). */
    fun profileNames(): Set<String> = profiles.keys

    /**
     * Patrones de la whitelist del vendor, como texto (Fase 5C: `allowedCommands` de
     * `get_device_profile`). Devuelve los PATRONES — jamás el producto cartesiano de
     * comandos posibles (error #45). Con [profile] resuelto, los del perfil.
     */
    fun patternsFor(vendor: Vendor, profile: String? = null): List<String> {
        profile?.let { profiles[it] }?.let { return it.allow.map { rx -> rx.pattern } }
        return whitelist[vendor].orEmpty().map { it.pattern }
    }

    fun validate(rawCommand: String, vendor: Vendor, profile: String? = null): ReadOnlyValidation {
        val command = rawCommand.trim()
        if (command.length < MIN_COMMAND_LENGTH) return rejected("comando vacío o demasiado corto")
        if (command.length > MAX_COMMAND_LENGTH) {
            return rejected("comando de ${command.length} caracteres — máximo $MAX_COMMAND_LENGTH")
        }

        if (command.any { it.code < 0x20 || it.code == 0x7f }) {
            return rejected("caracteres de control embebidos (posible inyección multilínea)")
        }
        if (!ALLOWED_CHARSET.matches(command)) {
            return rejected(
                "caracteres fuera del set permitido para comandos read-only " +
                    "(`;`, `&`, `<`, `>`, backtick, `$`, paréntesis y control chars están prohibidos)"
            )
        }

        val segments = command.split('|').map { it.trim() }
        if (segments.any { it.isEmpty() }) {
            return rejected("pipe vacío o encadenador `||` — no permitido en comandos read-only")
        }

        val head = segments.first()
        // Perfil del catálogo (v2) más específico que el vendor; sin perfil, el vendor.
        val resolvedProfile = profile?.let { profiles[it] }
        val allowPatterns = resolvedProfile?.allow ?: whitelist[vendor].orEmpty()
        val allowLabel = if (resolvedProfile != null) "del perfil `$profile`" else "de ${vendor.displayName}"
        if (allowPatterns.isEmpty()) {
            return rejected(
                "vendor `${vendor.displayName}` sin whitelist read-only (¿detección fallida?). " +
                    "No se adivina: verificá la sesión o usá `propose_commands`."
            )
        }
        if (allowPatterns.none { it.matches(head) }) {
            return rejected(
                "`$head` no matchea la whitelist read-only $allowLabel. " +
                    "Para comandos mutativos usá `propose_commands`."
            )
        }
        (deny + (resolvedProfile?.deny ?: emptyList())).firstOrNull { it.matches(head) }?.let {
            return rejected("`$head` está explícitamente excluido por la deny-list (`${it.pattern}`)")
        }

        for (filter in segments.drop(1)) {
            val firstToken = filter.split(Regex("\\s+")).first().lowercase()
            if (firstToken !in READ_PIPE_FILTERS) {
                return rejected(
                    "pipe hacia `$firstToken` no permitido: solo filtros de lectura " +
                        "(include/exclude/begin/section/match/count/…). Nada de redirect/tee/save/copy."
                )
            }
        }

        val risk = RiskClassifier.classifyLine(rawCommand, vendor).risk
        if (risk != RiskLevel.SAFE) {
            return rejected("el clasificador de riesgo marcó el comando como $risk — no es read-only")
        }
        return ReadOnlyValidation.Allowed
    }

    private fun rejected(reason: String) = ReadOnlyValidation.Rejected(reason)

    companion object {
        const val MIN_COMMAND_LENGTH = 2
        const val MAX_COMMAND_LENGTH = 512
        const val SUPPORTED_VERSION = 2

        /** Recurso embebido con la whitelist default. */
        const val EMBEDDED_RESOURCE = "/policies/readonly-commands.yaml"

        /** Override editable por el operador. Si existe, reemplaza al embebido completo. */
        val USER_OVERRIDE_PATH: Path =
            Path.of(System.getProperty("user.home"), ".opentermx", "policies", "readonly-commands.yaml")

        private val log = LoggerFactory.getLogger(ReadOnlyCommandValidator::class.java)
        private val yamlMapper: ObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

        /**
         * ASCII imprimible acotado: letras, dígitos, espacio y puntuación que aparece en
         * comandos de lectura reales (paths `flash:/x`, prefijos `10.0.0.0/24`, wildcards,
         * regex de filtros, comillas). Deliberadamente SIN `;`, `&`, `<`, `>`, `$`,
         * paréntesis ni backtick.
         */
        private val ALLOWED_CHARSET = Regex("""^[A-Za-z0-9 ._:/,\-+*?'"=@\[\]|\\^{}]+$""")

        /**
         * Primer token permitido tras un pipe: filtros de paginación/búsqueda que solo
         * transforman el output en pantalla. `redirect`, `tee`, `append`, `save`, `copy`,
         * `exec` y demás targets con efectos NO están acá a propósito.
         */
        private val READ_PIPE_FILTERS = setOf(
            // Cisco IOS/IOS-XE/NX-OS (incl. abreviaturas comunes)
            "include", "exclude", "begin", "section", "count", "i", "inc", "ex", "b",
            // JunOS
            "match", "except", "find", "last", "trim", "display", "no-more", "hold",
            // FortiOS
            "grep",
        )

        /**
         * Carga el validador default: override del usuario si existe y parsea; si no,
         * el recurso embebido. Nunca lanza — un YAML roto degrada al embebido con error
         * en el log.
         */
        fun default(): ReadOnlyCommandValidator {
            if (Files.isRegularFile(USER_OVERRIDE_PATH)) {
                runCatching { fromYaml(Files.readString(USER_OVERRIDE_PATH)) }
                    .onSuccess {
                        log.info("Whitelist read-only cargada del override {}", USER_OVERRIDE_PATH)
                        return it
                    }
                    .onFailure { e ->
                        log.error(
                            "Override {} inválido ({}). Usando whitelist embebida.",
                            USER_OVERRIDE_PATH, e.message,
                        )
                    }
            }
            return embedded()
        }

        fun embedded(): ReadOnlyCommandValidator {
            val text = ReadOnlyCommandValidator::class.java.getResourceAsStream(EMBEDDED_RESOURCE)
                ?.bufferedReader()?.use { it.readText() }
                ?: error("Recurso $EMBEDDED_RESOURCE ausente del classpath")
            return fromYaml(text)
        }

        /**
         * Parsea el YAML de whitelist. Lanza [IllegalArgumentException] si el shape no es
         * el esperado o alguna regex no compila — el caller decide el fallback.
         */
        fun fromYaml(yamlText: String): ReadOnlyCommandValidator {
            val root = yamlMapper.readValue(yamlText, WhitelistFile::class.java)
            require(root.version in 1..SUPPORTED_VERSION) {
                "whitelist versión ${root.version} no soportada (soporto 1..$SUPPORTED_VERSION)"
            }
            require(root.vendors.isNotEmpty()) { "whitelist sin sección `vendors`" }
            val byVendor = root.vendors.entries.associate { (name, patterns) ->
                val vendor = runCatching { Vendor.valueOf(name) }.getOrElse {
                    throw IllegalArgumentException("vendor desconocido en whitelist: `$name`")
                }
                vendor to patterns.map { compile(it) }
            }
            val profiles = root.profiles.entries.associate { (name, entry) ->
                require(entry.allow.isNotEmpty()) { "perfil `$name` sin sección `allow`" }
                name to Profile(
                    allow = entry.allow.map { compile(it) },
                    deny = entry.deny.map { compile(it) },
                )
            }
            return ReadOnlyCommandValidator(byVendor, root.deny.map { compile(it) }, profiles)
        }

        private fun compile(pattern: String): Regex {
            // Anclamos si el autor no lo hizo: una whitelist con `show` suelto que
            // matchee por substring sería un agujero.
            val anchored = buildString {
                if (!pattern.startsWith("^")) append('^')
                append(pattern)
                if (!pattern.endsWith("$")) append('$')
            }
            return Regex(anchored, RegexOption.IGNORE_CASE)
        }
    }

    private data class WhitelistFile(
        val version: Int = 1,
        val vendors: Map<String, List<String>> = emptyMap(),
        val deny: List<String> = emptyList(),
        /** v2 (Fase 6A): perfiles con nombre referenciados por el catálogo de modelos. */
        val profiles: Map<String, ProfileEntry> = emptyMap(),
    )

    private data class ProfileEntry(
        val allow: List<String> = emptyList(),
        val deny: List<String> = emptyList(),
        /** Documentación del pack; no se interpreta. */
        val notes: List<String> = emptyList(),
    )
}
