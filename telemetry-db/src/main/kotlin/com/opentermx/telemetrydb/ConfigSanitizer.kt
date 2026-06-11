package com.opentermx.telemetrydb

import java.security.MessageDigest

/**
 * Sanitización de secretos antes de persistir una running-config en `config_snapshots`
 * (regla #1 de persistencia y error #20 del catálogo): toda línea que matchee un patrón
 * de credencial conserva su estructura pero el valor se sustituye por `<REDACTED>`.
 *
 * El sha256 de deduplicación se calcula SOBRE EL TEXTO YA SANITIZADO — si se calculara
 * sobre el original, dos capturas idénticas con el mismo secreto deduplicarían pero el
 * mismo config con secreto rotado no, y peor: el hash filtraría información del secreto.
 */
object ConfigSanitizer {

    const val REDACTED = "<REDACTED>"

    /**
     * Claves que arrastran un secreto en la misma línea. El reemplazo conserva la
     * keyword (y el tipo de hash si lo hay, p. ej. `enable secret 5 ...`) y pisa el
     * resto de la línea.
     */
    private val SECRET_LINE = Regex(
        """(?i)^(\s*.*?\b(?:enable\s+secret|snmp-server\s+community|pre-shared-key|password|passwd|secret|community|key(?:-string)?)\b)(\s+.+)$""",
    )

    fun sanitize(configText: String): String =
        configText.lineSequence().joinToString("\n") { line ->
            val m = SECRET_LINE.find(line)
            if (m == null) line else "${m.groupValues[1]} $REDACTED"
        }

    /** sha256 hex (64 chars) del texto sanitizado. */
    fun sha256(sanitizedText: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(sanitizedText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
