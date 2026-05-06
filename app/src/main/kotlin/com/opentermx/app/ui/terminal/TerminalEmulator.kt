package com.opentermx.app.ui.terminal

class TerminalEmulator(val buffer: TerminalBuffer) : AnsiHandler {

    private val parser = AnsiParser(this)

    fun feed(text: String) = parser.feed(text)
    fun feedBytes(data: ByteArray, length: Int) = parser.feedBytes(data, length)

    override fun rowsForResize(): Int = buffer.rows
    override fun printChar(c: Char) = buffer.putChar(c)
    override fun bell() { /* could trigger audio/visual bell */ }
    override fun backspace() = buffer.backspace()
    override fun tab() = buffer.tab()
    override fun lineFeed() = buffer.lineFeed()
    override fun carriageReturn() = buffer.carriageReturn()
    override fun cursorUp(n: Int) = buffer.cursorUp(n)
    override fun cursorDown(n: Int) = buffer.cursorDown(n)
    override fun cursorForward(n: Int) = buffer.cursorForward(n)
    override fun cursorBackward(n: Int) = buffer.cursorBackward(n)
    override fun cursorNextLine(n: Int) = buffer.cursorNextLine(n)
    override fun cursorPrevLine(n: Int) = buffer.cursorPrevLine(n)
    override fun cursorTo(row: Int, col: Int) = buffer.cursorTo(row, col)
    override fun cursorToColumn(col: Int) = buffer.cursorToColumn(col)
    override fun cursorToRow(row: Int) = buffer.cursorToRow(row)
    override fun saveCursor() = buffer.saveCursor()
    override fun restoreCursor() = buffer.restoreCursor()
    override fun eraseInDisplay(mode: Int) = buffer.eraseInDisplay(mode)
    override fun eraseInLine(mode: Int) = buffer.eraseInLine(mode)
    override fun insertLines(n: Int) = buffer.insertLines(n)
    override fun deleteLines(n: Int) = buffer.deleteLines(n)
    override fun insertChars(n: Int) = buffer.insertChars(n)
    override fun deleteChars(n: Int) = buffer.deleteChars(n)
    override fun eraseChars(n: Int) = buffer.eraseChars(n)
    override fun scrollUpInRegion(n: Int) = buffer.scrollUpInRegion(n)
    override fun scrollDownInRegion(n: Int) = buffer.scrollDownInRegion(n)
    override fun setScrollRegion(top: Int, bottom: Int) = buffer.setScrollRegion(top, bottom)
    override fun index() = buffer.lineFeed()
    override fun reverseIndex() = buffer.reverseIndex()
    override fun nextLine() = buffer.nextLine()
    override fun reset() {
        buffer.reset()
        parser.reset()
    }

    override fun setMode(params: IntArray, isPrivate: Boolean) {
        if (!isPrivate) return
        for (p in params) when (p) {
            25 -> buffer.cursorVisible = true
            47, 1047 -> buffer.switchToAlternate()
            1048 -> buffer.saveCursor()
            1049 -> {
                buffer.saveCursor()
                buffer.switchToAlternate()
                buffer.eraseInDisplay(2)
            }
        }
    }

    override fun resetMode(params: IntArray, isPrivate: Boolean) {
        if (!isPrivate) return
        for (p in params) when (p) {
            25 -> buffer.cursorVisible = false
            47 -> buffer.switchToMain()
            1047 -> {
                buffer.eraseInDisplay(2)
                buffer.switchToMain()
            }
            1048 -> buffer.restoreCursor()
            1049 -> {
                buffer.switchToMain()
                buffer.restoreCursor()
            }
        }
    }

    override fun sgr(params: IntArray) {
        if (params.isEmpty()) {
            buffer.currentAttrs = CellAttributes.DEFAULT
            return
        }
        var i = 0
        while (i < params.size) {
            val p = params[i]
            when (p) {
                0 -> buffer.currentAttrs = CellAttributes.DEFAULT
                1 -> buffer.currentAttrs = buffer.currentAttrs.copy(bold = true)
                2 -> buffer.currentAttrs = buffer.currentAttrs.copy(dim = true)
                3 -> buffer.currentAttrs = buffer.currentAttrs.copy(italic = true)
                4 -> buffer.currentAttrs = buffer.currentAttrs.copy(underline = true)
                5, 6 -> buffer.currentAttrs = buffer.currentAttrs.copy(blink = true)
                7 -> buffer.currentAttrs = buffer.currentAttrs.copy(inverse = true)
                8 -> buffer.currentAttrs = buffer.currentAttrs.copy(hidden = true)
                9 -> buffer.currentAttrs = buffer.currentAttrs.copy(strikethrough = true)
                21, 22 -> buffer.currentAttrs = buffer.currentAttrs.copy(bold = false, dim = false)
                23 -> buffer.currentAttrs = buffer.currentAttrs.copy(italic = false)
                24 -> buffer.currentAttrs = buffer.currentAttrs.copy(underline = false)
                25 -> buffer.currentAttrs = buffer.currentAttrs.copy(blink = false)
                27 -> buffer.currentAttrs = buffer.currentAttrs.copy(inverse = false)
                28 -> buffer.currentAttrs = buffer.currentAttrs.copy(hidden = false)
                29 -> buffer.currentAttrs = buffer.currentAttrs.copy(strikethrough = false)
                in 30..37 -> buffer.currentAttrs = buffer.currentAttrs.copy(fg = AnsiColor.Indexed(p - 30))
                38 -> {
                    val parsed = parseExtendedColor(params, i + 1)
                    if (parsed != null) {
                        buffer.currentAttrs = buffer.currentAttrs.copy(fg = parsed.first)
                        i = parsed.second
                    }
                }
                39 -> buffer.currentAttrs = buffer.currentAttrs.copy(fg = AnsiColor.Default)
                in 40..47 -> buffer.currentAttrs = buffer.currentAttrs.copy(bg = AnsiColor.Indexed(p - 40))
                48 -> {
                    val parsed = parseExtendedColor(params, i + 1)
                    if (parsed != null) {
                        buffer.currentAttrs = buffer.currentAttrs.copy(bg = parsed.first)
                        i = parsed.second
                    }
                }
                49 -> buffer.currentAttrs = buffer.currentAttrs.copy(bg = AnsiColor.Default)
                in 90..97 -> buffer.currentAttrs = buffer.currentAttrs.copy(fg = AnsiColor.Indexed(p - 90 + 8))
                in 100..107 -> buffer.currentAttrs = buffer.currentAttrs.copy(bg = AnsiColor.Indexed(p - 100 + 8))
            }
            i++
        }
    }

    override fun osc(command: Int, data: String) {
        when (command) {
            0, 2 -> buffer.windowTitle = data
        }
    }

    private fun parseExtendedColor(params: IntArray, startIndex: Int): Pair<AnsiColor, Int>? {
        if (startIndex >= params.size) return null
        return when (params[startIndex]) {
            5 -> {
                if (startIndex + 1 >= params.size) null
                else Pair(AnsiColor.Indexed(params[startIndex + 1].coerceIn(0, 255)), startIndex + 1)
            }
            2 -> {
                if (startIndex + 3 >= params.size) null
                else Pair(
                    AnsiColor.Rgb(
                        params[startIndex + 1].coerceIn(0, 255),
                        params[startIndex + 2].coerceIn(0, 255),
                        params[startIndex + 3].coerceIn(0, 255),
                    ),
                    startIndex + 3,
                )
            }
            else -> null
        }
    }
}