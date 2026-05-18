package com.opentermx.app.ui.dialog

/**
 * Clasifica los errores típicos de conexión SSH/JSch a partir del texto del mensaje y
 * mapea cada caso a una sugerencia accionable para el operador. Phase 2.5 T4 lo introduce
 * para que el `ErrorDialog` no se limite a volcar el `jschProposal=…,serverProposal=…`
 * crudo: el usuario ahora ve qué tiene que tocar (KEX/cipher/MAC/hostkey).
 *
 * El resolver es una función pura sobre `String` — testeable sin levantar UI.
 */
enum class SshErrorTip {
    KEX_NEGOTIATION,
    CIPHER_NEGOTIATION,
    MAC_NEGOTIATION,
    HOSTKEY_NEGOTIATION,
    HOSTKEY_UNKNOWN_OR_CHANGED;

    /** True si el tip tiene sentido resolverse abriendo Setup → SSH General. */
    val opensSshGeneral: Boolean get() = when (this) {
        KEX_NEGOTIATION, CIPHER_NEGOTIATION, MAC_NEGOTIATION, HOSTKEY_NEGOTIATION -> true
        HOSTKEY_UNKNOWN_OR_CHANGED -> false
    }

    companion object {
        /**
         * Inspecciona [message] (y, si aplica, [cause]) buscando los marcadores que JSch deja
         * en el `Algorithm negotiation fail` y en los errores de hostkey. Devuelve `null` si
         * el error no encaja en ningún caso conocido — el dialog cae al render genérico.
         */
        fun resolve(message: String?, cause: Throwable? = null): SshErrorTip? {
            val text = buildString {
                if (!message.isNullOrBlank()) appendLine(message)
                var t: Throwable? = cause
                var depth = 0
                while (t != null && depth < 6) {
                    t.message?.let { appendLine(it) }
                    t = t.cause
                    depth++
                }
            }
            if (text.isBlank()) return null

            val lower = text.lowercase()
            // Hostkey desconocido/cambiado: JSch lanza `UnknownHostKey` o `HostKey has been changed`,
            // y nuestro `JavaFxHostKeyVerifier` reemite el mensaje con esas keywords.
            if ("unknownhostkey" in lower || "unknown host key" in lower ||
                "host key has been changed" in lower || "hostkey has been changed" in lower) {
                return HOSTKEY_UNKNOWN_OR_CHANGED
            }
            // `Algorithm negotiation fail`: JSch incluye `algorithmName=…` con kex/cipher/mac/host_key.
            val isNegotiationFail = "algorithm negotiation fail" in lower || "negotiation fail" in lower
            if (isNegotiationFail) {
                val algoName = ALGO_NAME_REGEX.find(text)?.groupValues?.getOrNull(1)?.lowercase()
                when (algoName) {
                    "kex" -> return KEX_NEGOTIATION
                    "cipher", "cipher.s2c", "cipher.c2s" -> return CIPHER_NEGOTIATION
                    "mac", "mac.s2c", "mac.c2s" -> return MAC_NEGOTIATION
                    "host_key", "hostkey", "server_host_key" -> return HOSTKEY_NEGOTIATION
                }
                // Sin algorithmName parseable — heurística sobre keywords sueltas en el mensaje.
                if ("kex" in lower) return KEX_NEGOTIATION
                if ("cipher" in lower) return CIPHER_NEGOTIATION
                if (Regex("\\bmac\\b").containsMatchIn(lower)) return MAC_NEGOTIATION
                if ("host_key" in lower || "hostkey" in lower) return HOSTKEY_NEGOTIATION
            }
            return null
        }

        private val ALGO_NAME_REGEX = Regex("""algorithmName\s*=\s*["']?([A-Za-z0-9_.]+)["']?""")
    }
}
