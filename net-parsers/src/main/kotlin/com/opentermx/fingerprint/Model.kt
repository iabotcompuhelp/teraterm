package com.opentermx.fingerprint

import com.opentermx.netparsers.Vendor

/** Confianza de la identidad inferida. Alineado al `confidence_t` de la migración V2 (Fase 5B). */
enum class Confidence { HIGH, MEDIUM, LOW }

/**
 * Identidad de un dispositivo según su propio `show version` (o equivalente). Subfase 5A.
 *
 * Reglas del modelo:
 *  - [serialNumbers] es lista porque stacks y pares HA reportan N seriales (error #35).
 *  - [uptimeText] queda CRUDO: los formatos de uptime no valen el esfuerzo de parsear
 *    (error #37) — si algún día hace falta la duración, se agrega un parser con tests.
 *  - [hostname] proviene del equipo: dato NO confiable (se declara en `untrustedFields`
 *    de las tools de la Fase 5C).
 *  - [extras] lleva pares vendor-specific que el modelo no normaliza — hoy
 *    `stackMembers` cuando los modelos de un stack difieren del master (error #35).
 */
data class DeviceIdentity(
    val vendor: Vendor,
    val model: String?,
    val osVersion: String?,
    val serialNumbers: List<String> = emptyList(),
    val hostname: String?,
    val uptimeText: String?,
    val haRole: String? = null,
    val confidence: Confidence,
    val extras: Map<String, String> = emptyMap(),
) {
    /** Proyección JSON canónica (los fixtures comparan en modo STRICT). */
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "vendor" to vendor.name,
        "model" to model,
        "osVersion" to osVersion,
        "serialNumbers" to serialNumbers,
        "hostname" to hostname,
        "uptimeText" to uptimeText,
        "haRole" to haRole,
        "confidence" to confidence.name,
    )
}

/**
 * Sonda de fingerprinting (chain of responsibility, subfase 5A). Cada sonda sabe UN
 * comando read-only de identificación y cómo interpretar su output.
 *
 * Contrato:
 *  - [matches] y [extract] NUNCA lanzan ante basura: input irreconocible => `false`/`null`.
 *  - [extract] devuelve `null` solo si el output matcheó superficialmente pero no se
 *    pudo extraer nada útil — el orquestador lo trata como no-match.
 *  - [secondaryCommand] es un comando opcional de enriquecimiento que se ejecuta SOLO
 *    tras un match (MikroTik: `/system routerboard print` agrega modelo y serial).
 *    [enrich] nunca degrada la identidad: ante output inútil devuelve la misma.
 */
interface FingerprintProbe {
    /** Identificador estable para logs y la columna `probe_id` de la Fase 5B. */
    val id: String

    /** Orden de intento en la cadena (menor = primero). */
    val order: Int

    /** Vendor objetivo: define contra QUÉ whitelist read-only se valida [command]. */
    val vendor: Vendor

    /** Comando read-only que la sonda envía (siempre completo — error #32). */
    val command: String

    val secondaryCommand: String? get() = null

    fun matches(output: String): Boolean

    fun extract(output: String): DeviceIdentity?

    fun enrich(identity: DeviceIdentity, secondaryOutput: String): DeviceIdentity = identity
}

/**
 * Detección de "el equipo no entendió el comando" (error #33): es un NO-MATCH esperado
 * del encadenamiento de sondas, NUNCA un error de sesión. Cubre los rechazos típicos de
 * cada CLI soportada.
 */
object CliRejection {

    private val patterns = listOf(
        Regex("""%\s*Invalid input""", RegexOption.IGNORE_CASE),
        Regex("""Invalid input detected""", RegexOption.IGNORE_CASE),
        Regex("""%\s*(Unknown|Unrecognized|Incomplete|Ambiguous) command""", RegexOption.IGNORE_CASE),
        Regex("""^Unknown action\b""", RegexOption.IGNORE_CASE),
        Regex("""(?m)^\s*\^\s*$"""),                       // marcador `^` de Cisco/Huawei
        Regex("""(?m)^Error:"""),
        Regex("""command parse error""", RegexOption.IGNORE_CASE),
        Regex("""bad command name""", RegexOption.IGNORE_CASE),   // MikroTik
        Regex("""syntax error""", RegexOption.IGNORE_CASE),
    )

    fun isRejection(output: String): Boolean {
        if (output.isBlank()) return true
        return patterns.any { it.containsMatchIn(output) }
    }
}
