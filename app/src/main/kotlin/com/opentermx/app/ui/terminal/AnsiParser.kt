package com.opentermx.app.ui.terminal

interface AnsiHandler {
    fun printChar(c: Char)
    fun bell()
    fun backspace()
    fun tab()
    fun lineFeed()
    fun carriageReturn()
    fun cursorUp(n: Int)
    fun cursorDown(n: Int)
    fun cursorForward(n: Int)
    fun cursorBackward(n: Int)
    fun cursorNextLine(n: Int)
    fun cursorPrevLine(n: Int)
    fun cursorTo(row: Int, col: Int)
    fun cursorToColumn(col: Int)
    fun cursorToRow(row: Int)
    fun saveCursor()
    fun restoreCursor()
    fun eraseInDisplay(mode: Int)
    fun eraseInLine(mode: Int)
    fun insertLines(n: Int)
    fun deleteLines(n: Int)
    fun insertChars(n: Int)
    fun deleteChars(n: Int)
    fun eraseChars(n: Int)
    fun scrollUpInRegion(n: Int)
    fun scrollDownInRegion(n: Int)
    fun setScrollRegion(top: Int, bottom: Int)
    fun setMode(params: IntArray, isPrivate: Boolean)
    fun resetMode(params: IntArray, isPrivate: Boolean)
    fun sgr(params: IntArray)
    fun index()
    fun reverseIndex()
    fun nextLine()
    fun reset()
    fun osc(command: Int, data: String)
    fun rowsForResize(): Int
}

class AnsiParser(private val handler: AnsiHandler) {

    private enum class State {
        GROUND, ESCAPE, ESCAPE_INTERMEDIATE,
        CSI_ENTRY, CSI_PARAM, CSI_INTERMEDIATE, CSI_IGNORE,
        OSC_STRING, ST_WAIT,
    }

    private var state = State.GROUND
    private val params = ArrayList<Int>(8)
    private var currentParam: Int? = null
    private var privateMarker: Char = ' '
    private val intermediate = StringBuilder()
    private val osc = StringBuilder()

    fun feed(text: String) {
        for (c in text) handle(c)
    }

    fun feedBytes(data: ByteArray, length: Int) {
        feed(String(data, 0, length, Charsets.UTF_8))
    }

    fun reset() {
        state = State.GROUND
        params.clear()
        currentParam = null
        intermediate.setLength(0)
        osc.setLength(0)
        privateMarker = ' '
    }

    private fun handle(c: Char) {
        when (state) {
            State.GROUND -> ground(c)
            State.ESCAPE -> escape(c)
            State.ESCAPE_INTERMEDIATE -> escapeIntermediate(c)
            State.CSI_ENTRY -> csiEntry(c)
            State.CSI_PARAM -> csiParam(c)
            State.CSI_INTERMEDIATE -> csiIntermediate(c)
            State.CSI_IGNORE -> csiIgnore(c)
            State.OSC_STRING -> oscString(c)
            State.ST_WAIT -> stWait(c)
        }
    }

    private fun ground(c: Char) {
        when (c.code) {
            0x07 -> handler.bell()
            0x08 -> handler.backspace()
            0x09 -> handler.tab()
            0x0A, 0x0B, 0x0C -> handler.lineFeed()
            0x0D -> handler.carriageReturn()
            0x1B -> { state = State.ESCAPE; intermediate.setLength(0) }
            in 0x00..0x1F -> { /* ignore other C0 */ }
            0x7F -> { /* DEL — ignore */ }
            else -> handler.printChar(c)
        }
    }

    private fun escape(c: Char) {
        when (c) {
            '[' -> {
                state = State.CSI_ENTRY
                params.clear(); currentParam = null
                privateMarker = ' '
                intermediate.setLength(0)
            }
            ']' -> { state = State.OSC_STRING; osc.setLength(0) }
            '7' -> { handler.saveCursor(); state = State.GROUND }
            '8' -> { handler.restoreCursor(); state = State.GROUND }
            'D' -> { handler.index(); state = State.GROUND }
            'M' -> { handler.reverseIndex(); state = State.GROUND }
            'E' -> { handler.nextLine(); state = State.GROUND }
            'c' -> { handler.reset(); state = State.GROUND }
            in ' '..'/' -> { intermediate.append(c); state = State.ESCAPE_INTERMEDIATE }
            else -> state = State.GROUND
        }
    }

    private fun escapeIntermediate(c: Char) {
        if (c.code in 0x30..0x7E) state = State.GROUND
        else if (c.code in 0x20..0x2F) intermediate.append(c)
        else state = State.GROUND
    }

    private fun csiEntry(c: Char) {
        when {
            c == '?' || c == '<' || c == '=' || c == '>' -> {
                privateMarker = c
                state = State.CSI_PARAM
            }
            c.isDigit() -> {
                currentParam = c - '0'
                state = State.CSI_PARAM
            }
            c == ';' -> {
                params.add(0)
                state = State.CSI_PARAM
            }
            c.code in 0x20..0x2F -> {
                intermediate.append(c)
                state = State.CSI_INTERMEDIATE
            }
            c.code in 0x40..0x7E -> {
                dispatchCsi(c)
                state = State.GROUND
            }
            else -> state = State.CSI_IGNORE
        }
    }

    private fun csiParam(c: Char) {
        when {
            c.isDigit() -> currentParam = (currentParam ?: 0) * 10 + (c - '0')
            c == ';' || c == ':' -> {
                params.add(currentParam ?: 0); currentParam = null
            }
            c.code in 0x20..0x2F -> {
                if (currentParam != null) { params.add(currentParam!!); currentParam = null }
                intermediate.append(c)
                state = State.CSI_INTERMEDIATE
            }
            c.code in 0x40..0x7E -> {
                if (currentParam != null) { params.add(currentParam!!); currentParam = null }
                dispatchCsi(c)
                state = State.GROUND
            }
            else -> state = State.CSI_IGNORE
        }
    }

    private fun csiIntermediate(c: Char) {
        when {
            c.code in 0x20..0x2F -> intermediate.append(c)
            c.code in 0x40..0x7E -> {
                dispatchCsi(c); state = State.GROUND
            }
            else -> state = State.CSI_IGNORE
        }
    }

    private fun csiIgnore(c: Char) {
        if (c.code in 0x40..0x7E) state = State.GROUND
    }

    private fun oscString(c: Char) {
        when (c.code) {
            0x07 -> { dispatchOsc(); state = State.GROUND }
            0x1B -> { state = State.ST_WAIT }
            else -> osc.append(c)
        }
    }

    private fun stWait(c: Char) {
        dispatchOsc()
        state = State.GROUND
        if (c != '\\') handle(c)
    }

    private fun dispatchCsi(final: Char) {
        val p = params.toIntArray()
        val priv = privateMarker == '?'
        when (final) {
            '@' -> handler.insertChars(p.firstOrDefault(1))
            'A' -> handler.cursorUp(p.firstOrDefault(1))
            'B' -> handler.cursorDown(p.firstOrDefault(1))
            'C' -> handler.cursorForward(p.firstOrDefault(1))
            'D' -> handler.cursorBackward(p.firstOrDefault(1))
            'E' -> handler.cursorNextLine(p.firstOrDefault(1))
            'F' -> handler.cursorPrevLine(p.firstOrDefault(1))
            'G' -> handler.cursorToColumn(p.firstOrDefault(1) - 1)
            'H', 'f' -> handler.cursorTo(
                (p.getOrZero(0).coerceAtLeast(1)) - 1,
                (p.getOrZero(1).coerceAtLeast(1)) - 1,
            )
            'J' -> handler.eraseInDisplay(p.getOrZero(0))
            'K' -> handler.eraseInLine(p.getOrZero(0))
            'L' -> handler.insertLines(p.firstOrDefault(1))
            'M' -> handler.deleteLines(p.firstOrDefault(1))
            'P' -> handler.deleteChars(p.firstOrDefault(1))
            'S' -> handler.scrollUpInRegion(p.firstOrDefault(1))
            'T' -> handler.scrollDownInRegion(p.firstOrDefault(1))
            'X' -> handler.eraseChars(p.firstOrDefault(1))
            'd' -> handler.cursorToRow(p.firstOrDefault(1) - 1)
            'h' -> handler.setMode(p, priv)
            'l' -> handler.resetMode(p, priv)
            'm' -> handler.sgr(p)
            'r' -> {
                val top = (p.getOrZero(0).coerceAtLeast(1)) - 1
                val bot = (if (p.size >= 2) p[1].coerceAtLeast(1) else handler.rowsForResize()) - 1
                handler.setScrollRegion(top, bot)
            }
            's' -> handler.saveCursor()
            'u' -> handler.restoreCursor()
            else -> { /* unsupported, ignore */ }
        }
    }

    private fun dispatchOsc() {
        val s = osc.toString()
        val sep = s.indexOf(';')
        if (sep <= 0) return
        val cmd = s.substring(0, sep).toIntOrNull() ?: return
        val data = s.substring(sep + 1)
        handler.osc(cmd, data)
    }
}

private fun IntArray.firstOrDefault(default: Int): Int =
    if (isEmpty()) default else this[0].let { if (it == 0) default else it }

private fun IntArray.getOrZero(index: Int): Int =
    if (index < size) this[index] else 0