package com.opentermx.netparsers

/**
 * Normalización de números con formato de equipo de red (error #14 del catálogo):
 * separadores de miles, unidades pegadas (`10000full`), unidades con espacio
 * (`10 Gb/s`), y la convención "número pelado = Mbps" (Huawei `Speed :  1000`).
 * Todo parse fallido devuelve `null`, nunca lanza.
 */
internal object Numbers {

    fun toLong(text: String?): Long? = text?.replace(",", "")?.trim()?.toLongOrNull()

    fun toInt(text: String?): Int? = text?.replace(",", "")?.trim()?.toIntOrNull()

    /**
     * Texto de velocidad → bits por segundo.
     *  - `1000Mb/s`, `10 Gb/s`, `1Gbps`, `100 Mbit` → unidad explícita;
     *  - `10000full` / `1000half` (FortiOS) → sufijo de duplex, unidad implícita Mbps;
     *  - `1000` pelado → Mbps (convención Huawei);
     *  - `Auto-speed`, `auto`, vacío → null (NO inventar la velocidad nominal).
     */
    fun speedToBps(text: String?): Long? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty() || t.contains("auto", ignoreCase = true)) return null
        val m = SPEED.find(t) ?: return null
        val value = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].lowercase()
        val factor = when {
            unit.startsWith("g") -> 1_000_000_000.0
            unit.startsWith("m") -> 1_000_000.0
            unit.startsWith("k") -> 1_000.0
            // Sin unidad o sufijo de duplex (full/half): convención Mbps.
            else -> 1_000_000.0
        }
        return (value * factor).toLong()
    }

    /** `Full-duplex` / `half` / `Auto-Duplex` / `10000full` → [Duplex]. */
    fun duplexOf(text: String?): Duplex? = when {
        text == null -> null
        text.contains("full", ignoreCase = true) -> Duplex.FULL
        text.contains("half", ignoreCase = true) -> Duplex.HALF
        text.contains("auto", ignoreCase = true) -> Duplex.AUTO
        else -> null
    }

    private val SPEED = Regex("""([\d,.]+)\s*([A-Za-z/]*)""")
}
