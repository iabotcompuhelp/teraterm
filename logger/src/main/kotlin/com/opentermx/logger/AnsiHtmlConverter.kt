package com.opentermx.logger

class AnsiHtmlConverter {

    private enum class State { GROUND, ESCAPE, CSI, OSC }

    private var state = State.GROUND
    private val csiBuffer = StringBuilder()
    private var current = HtmlStyle()
    private var emittedSpan: HtmlStyle? = null
    private val out = StringBuilder()

    fun convertChunk(text: String): String {
        out.setLength(0)
        for (c in text) handle(c)
        return out.toString()
    }

    fun closingTags(): String = if (emittedSpan != null) "</span>" else ""

    private fun handle(c: Char) {
        when (state) {
            State.GROUND -> when (c.code) {
                0x1B -> state = State.ESCAPE
                0x07, 0x08, 0x00 -> { /* skip BEL/BS/NUL */ }
                else -> emit(c)
            }
            State.ESCAPE -> when (c) {
                '[' -> { state = State.CSI; csiBuffer.setLength(0) }
                ']' -> state = State.OSC
                else -> state = State.GROUND
            }
            State.CSI -> when {
                c.code in 0x40..0x7E -> {
                    if (c == 'm') applySgr(csiBuffer.toString())
                    state = State.GROUND
                }
                c.code in 0x20..0x3F -> csiBuffer.append(c)
                else -> state = State.GROUND
            }
            State.OSC -> if (c.code == 0x07 || c.code == 0x1B) state = State.GROUND
        }
    }

    private fun emit(c: Char) {
        if (current != emittedSpan) {
            if (emittedSpan != null) out.append("</span>")
            if (current != HtmlStyle()) {
                out.append("<span style=\"").append(current.toCss()).append("\">")
            }
            emittedSpan = current
        }
        when (c.code) {
            0x3C -> out.append("&lt;")
            0x3E -> out.append("&gt;")
            0x26 -> out.append("&amp;")
            0x0D -> { /* ignore CR */ }
            else -> out.append(c)
        }
    }

    private fun applySgr(params: String) {
        val parts = if (params.isBlank()) listOf(0) else params.split(";").mapNotNull { it.toIntOrNull() }
        var i = 0
        while (i < parts.size) {
            val p = parts[i]
            when (p) {
                0 -> current = HtmlStyle()
                1 -> current = current.copy(bold = true)
                3 -> current = current.copy(italic = true)
                4 -> current = current.copy(underline = true)
                22 -> current = current.copy(bold = false)
                23 -> current = current.copy(italic = false)
                24 -> current = current.copy(underline = false)
                in 30..37 -> current = current.copy(fg = AnsiPaletteHtml.basic(p - 30))
                38 -> {
                    val parsed = parseExtended(parts, i + 1)
                    if (parsed != null) {
                        current = current.copy(fg = parsed.first)
                        i = parsed.second
                    }
                }
                39 -> current = current.copy(fg = null)
                in 40..47 -> current = current.copy(bg = AnsiPaletteHtml.basic(p - 40))
                48 -> {
                    val parsed = parseExtended(parts, i + 1)
                    if (parsed != null) {
                        current = current.copy(bg = parsed.first)
                        i = parsed.second
                    }
                }
                49 -> current = current.copy(bg = null)
                in 90..97 -> current = current.copy(fg = AnsiPaletteHtml.bright(p - 90))
                in 100..107 -> current = current.copy(bg = AnsiPaletteHtml.bright(p - 100))
            }
            i++
        }
    }

    private fun parseExtended(parts: List<Int>, startIndex: Int): Pair<String, Int>? {
        if (startIndex >= parts.size) return null
        return when (parts[startIndex]) {
            5 -> {
                if (startIndex + 1 >= parts.size) null
                else Pair(AnsiPaletteHtml.indexed(parts[startIndex + 1].coerceIn(0, 255)), startIndex + 1)
            }
            2 -> {
                if (startIndex + 3 >= parts.size) null
                else {
                    val r = parts[startIndex + 1].coerceIn(0, 255)
                    val g = parts[startIndex + 2].coerceIn(0, 255)
                    val b = parts[startIndex + 3].coerceIn(0, 255)
                    Pair("rgb($r,$g,$b)", startIndex + 3)
                }
            }
            else -> null
        }
    }
}

data class HtmlStyle(
    val fg: String? = null,
    val bg: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
) {
    fun toCss(): String = buildString {
        fg?.let { append("color:").append(it).append(";") }
        bg?.let { append("background:").append(it).append(";") }
        if (bold) append("font-weight:bold;")
        if (italic) append("font-style:italic;")
        if (underline) append("text-decoration:underline;")
    }
}

object AnsiPaletteHtml {
    private val basicHex = arrayOf(
        "#000000", "#cd0000", "#00cd00", "#cdcd00",
        "#1e90ff", "#cd00cd", "#00cdcd", "#e5e5e5",
    )
    private val brightHex = arrayOf(
        "#7f7f7f", "#ff5555", "#55ff55", "#ffff55",
        "#5c5cff", "#ff55ff", "#55ffff", "#ffffff",
    )

    fun basic(idx: Int): String = basicHex.getOrElse(idx) { "#000000" }
    fun bright(idx: Int): String = brightHex.getOrElse(idx) { "#ffffff" }
    fun indexed(idx: Int): String = when {
        idx < 8 -> basic(idx)
        idx < 16 -> bright(idx - 8)
        idx < 232 -> {
            val n = idx - 16
            "rgb(${cube(n / 36)},${cube((n / 6) % 6)},${cube(n % 6)})"
        }
        else -> {
            val v = 8 + (idx - 232) * 10
            "rgb($v,$v,$v)"
        }
    }

    private fun cube(v: Int) = if (v == 0) 0 else 55 + v * 40
}