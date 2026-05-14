package com.opentermx.app.ui.terminal

class TerminalBuffer(
    initialCols: Int = 80,
    initialRows: Int = 24,
    scrollbackLimit: Int = 10_000,
) {
    var scrollbackLimit: Int = scrollbackLimit.coerceAtLeast(0)
        set(value) {
            field = value.coerceAtLeast(0)
            // Apply the new ceiling right away so shrinking takes effect on already-stored
            // history. The +rows accounts for the visible viewport always living in `lines`.
            while (lines.size > field + rows) {
                lines.removeFirst()
                cursorRow = (cursorRow - 1).coerceAtLeast(0)
            }
            touch()
        }
    var cols: Int = initialCols
        private set
    var rows: Int = initialRows
        private set

    private val lines = ArrayDeque<MutableList<TerminalCell>>()
    private var savedMain: SavedScreen? = null

    var cursorRow: Int = 0
        private set
    var cursorCol: Int = 0
        private set
    var currentAttrs: CellAttributes = CellAttributes.DEFAULT

    private var savedCursorRowVis: Int = 0
    private var savedCursorCol: Int = 0
    private var savedAttrs: CellAttributes = CellAttributes.DEFAULT

    private var scrollTopVis: Int = 0
    private var scrollBottomVis: Int = initialRows - 1

    var alternateMode: Boolean = false
        private set
    var cursorVisible: Boolean = true
    var windowTitle: String = ""

    var version: Long = 0
        private set

    init {
        repeat(rows) { lines.add(blankLine()) }
    }

    val totalLines: Int get() = lines.size
    val visibleTop: Int get() = lines.size - rows

    fun lineAt(absoluteRow: Int): List<TerminalCell>? =
        if (absoluteRow in 0 until lines.size) lines[absoluteRow] else null

    /**
     * Devuelve las últimas [count] líneas del scrollback + viewport como `String` plano
     * (sin atributos, sin trailing whitespace). Útil para alimentar al AI Assistant
     * (contexto del terminal) y al endpoint REST `GET /api/terminal/buffer`.
     */
    fun snapshotLastLines(count: Int): List<String> {
        if (count <= 0 || lines.isEmpty()) return emptyList()
        val from = (lines.size - count).coerceAtLeast(0)
        val out = ArrayList<String>(lines.size - from)
        for (i in from until lines.size) {
            val sb = StringBuilder(cols)
            for (cell in lines[i]) sb.append(cell.char)
            // recorta espacios al final
            var end = sb.length
            while (end > 0 && sb[end - 1] == ' ') end--
            out.add(sb.substring(0, end))
        }
        return out
    }

    fun cellAt(absoluteRow: Int, col: Int): TerminalCell {
        val line = lines.getOrNull(absoluteRow) ?: return TerminalCell.EMPTY
        return line.getOrNull(col) ?: TerminalCell.EMPTY
    }

    private fun blankLine(width: Int = cols): MutableList<TerminalCell> =
        MutableList(width) { TerminalCell.EMPTY }

    private fun touch() { version++ }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        if (newCols <= 0 || newRows <= 0) return
        for (line in lines) {
            while (line.size < newCols) line.add(TerminalCell.EMPTY)
            while (line.size > newCols) line.removeLast()
        }
        if (lines.size < newRows) {
            repeat(newRows - lines.size) { lines.addLast(blankLine(newCols)) }
        }
        cols = newCols
        rows = newRows
        scrollTopVis = 0
        scrollBottomVis = rows - 1
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        cursorRow = cursorRow.coerceIn(0, lines.size - 1)
        touch()
    }

    fun putChar(c: Char) {
        if (cursorCol >= cols) {
            cursorCol = 0
            lineFeed()
        }
        ensureLine(cursorRow)
        val line = lines[cursorRow]
        while (line.size <= cursorCol) line.add(TerminalCell.EMPTY)
        line[cursorCol] = TerminalCell(c, currentAttrs)
        cursorCol++
        touch()
    }

    fun lineFeed() {
        val visRow = cursorRow - visibleTop
        if (visRow == scrollBottomVis) {
            scrollUp(scrollTopVis, scrollBottomVis, 1)
        } else {
            cursorRow++
            ensureLine(cursorRow)
        }
        touch()
    }

    fun reverseIndex() {
        val visRow = cursorRow - visibleTop
        if (visRow == scrollTopVis) {
            scrollDown(scrollTopVis, scrollBottomVis, 1)
        } else {
            cursorRow--
        }
        touch()
    }

    fun nextLine() {
        cursorCol = 0
        lineFeed()
    }

    fun carriageReturn() { cursorCol = 0; touch() }
    fun backspace() { if (cursorCol > 0) cursorCol--; touch() }

    fun tab() {
        cursorCol = ((cursorCol / 8) + 1) * 8
        if (cursorCol >= cols) cursorCol = cols - 1
        touch()
    }

    fun cursorUp(n: Int) {
        val limit = visibleTop + scrollTopVis
        cursorRow = (cursorRow - n).coerceAtLeast(limit)
        touch()
    }

    fun cursorDown(n: Int) {
        val limit = visibleTop + scrollBottomVis
        cursorRow = (cursorRow + n).coerceAtMost(limit)
        ensureLine(cursorRow)
        touch()
    }

    fun cursorForward(n: Int) {
        cursorCol = (cursorCol + n).coerceAtMost(cols - 1)
        touch()
    }

    fun cursorBackward(n: Int) {
        cursorCol = (cursorCol - n).coerceAtLeast(0)
        touch()
    }

    fun cursorTo(visRow: Int, col: Int) {
        cursorRow = visibleTop + visRow.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, cols - 1)
        ensureLine(cursorRow)
        touch()
    }

    fun cursorToColumn(col: Int) {
        cursorCol = col.coerceIn(0, cols - 1)
        touch()
    }

    fun cursorToRow(visRow: Int) {
        cursorRow = visibleTop + visRow.coerceIn(0, rows - 1)
        ensureLine(cursorRow)
        touch()
    }

    fun cursorNextLine(n: Int) {
        cursorDown(n)
        cursorCol = 0
    }

    fun cursorPrevLine(n: Int) {
        cursorUp(n)
        cursorCol = 0
    }

    fun saveCursor() {
        savedCursorRowVis = cursorRow - visibleTop
        savedCursorCol = cursorCol
        savedAttrs = currentAttrs
    }

    fun restoreCursor() {
        cursorRow = visibleTop + savedCursorRowVis
        cursorCol = savedCursorCol
        currentAttrs = savedAttrs
        ensureLine(cursorRow)
        touch()
    }

    fun eraseInDisplay(mode: Int) {
        val firstVis = visibleTop
        val lastVis = visibleTop + rows - 1
        when (mode) {
            0 -> {
                eraseInLineRange(cursorRow, cursorCol, cols)
                for (r in (cursorRow + 1)..lastVis) eraseLine(r)
            }
            1 -> {
                for (r in firstVis until cursorRow) eraseLine(r)
                eraseInLineRange(cursorRow, 0, cursorCol + 1)
            }
            2 -> for (r in firstVis..lastVis) eraseLine(r)
            3 -> {
                while (lines.size > rows) lines.removeFirst()
                cursorRow = cursorRow.coerceAtLeast(0)
            }
        }
        touch()
    }

    fun eraseInLine(mode: Int) {
        when (mode) {
            0 -> eraseInLineRange(cursorRow, cursorCol, cols)
            1 -> eraseInLineRange(cursorRow, 0, cursorCol + 1)
            2 -> eraseLine(cursorRow)
        }
        touch()
    }

    fun eraseChars(n: Int) {
        eraseInLineRange(cursorRow, cursorCol, (cursorCol + n).coerceAtMost(cols))
        touch()
    }

    fun insertLines(n: Int) {
        val visRow = cursorRow - visibleTop
        if (visRow !in scrollTopVis..scrollBottomVis) return
        repeat(n.coerceAtMost(scrollBottomVis - visRow + 1)) {
            val absBottom = visibleTop + scrollBottomVis
            if (absBottom < lines.size) lines.removeAt(absBottom)
            lines.add(cursorRow, blankLine())
        }
        touch()
    }

    fun deleteLines(n: Int) {
        val visRow = cursorRow - visibleTop
        if (visRow !in scrollTopVis..scrollBottomVis) return
        repeat(n.coerceAtMost(scrollBottomVis - visRow + 1)) {
            if (cursorRow < lines.size) lines.removeAt(cursorRow)
            val absBottom = (visibleTop + scrollBottomVis).coerceAtMost(lines.size)
            lines.add(absBottom, blankLine())
        }
        touch()
    }

    fun insertChars(n: Int) {
        ensureLine(cursorRow)
        val line = lines[cursorRow]
        while (line.size < cols) line.add(TerminalCell.EMPTY)
        repeat(n.coerceAtMost(cols - cursorCol)) {
            line.add(cursorCol, TerminalCell(' ', currentAttrs))
            line.removeLast()
        }
        touch()
    }

    fun deleteChars(n: Int) {
        ensureLine(cursorRow)
        val line = lines[cursorRow]
        while (line.size < cols) line.add(TerminalCell.EMPTY)
        repeat(n.coerceAtMost(cols - cursorCol)) {
            if (cursorCol < line.size) line.removeAt(cursorCol)
            line.add(TerminalCell.EMPTY)
        }
        touch()
    }

    fun setScrollRegion(topVis: Int, bottomVis: Int) {
        scrollTopVis = topVis.coerceIn(0, rows - 1)
        scrollBottomVis = bottomVis.coerceIn(scrollTopVis, rows - 1)
        cursorTo(0, 0)
    }

    fun scrollUpInRegion(n: Int) { scrollUp(scrollTopVis, scrollBottomVis, n); touch() }
    fun scrollDownInRegion(n: Int) { scrollDown(scrollTopVis, scrollBottomVis, n); touch() }

    private fun scrollUp(topVis: Int, bottomVis: Int, count: Int) {
        if (topVis == 0 && bottomVis == rows - 1 && !alternateMode) {
            repeat(count) {
                lines.addLast(blankLine())
                while (lines.size > scrollbackLimit + rows) {
                    lines.removeFirst()
                    cursorRow = (cursorRow - 1).coerceAtLeast(0)
                }
            }
        } else {
            val absTop = visibleTop + topVis
            val absBottom = visibleTop + bottomVis
            repeat(count) {
                if (absTop < lines.size) {
                    lines.removeAt(absTop)
                    val insertAt = (absBottom).coerceAtMost(lines.size)
                    lines.add(insertAt, blankLine())
                }
            }
        }
    }

    private fun scrollDown(topVis: Int, bottomVis: Int, count: Int) {
        val absTop = visibleTop + topVis
        val absBottom = visibleTop + bottomVis
        repeat(count) {
            if (absBottom < lines.size) {
                lines.removeAt(absBottom)
                lines.add(absTop, blankLine())
            }
        }
    }

    private fun eraseLine(absRow: Int) {
        if (absRow !in 0 until lines.size) return
        val line = lines[absRow]
        for (i in line.indices) line[i] = TerminalCell.EMPTY
    }

    private fun eraseInLineRange(absRow: Int, fromCol: Int, toColExclusive: Int) {
        if (absRow !in 0 until lines.size) return
        val line = lines[absRow]
        while (line.size < toColExclusive) line.add(TerminalCell.EMPTY)
        for (c in fromCol until toColExclusive.coerceAtMost(line.size)) {
            line[c] = TerminalCell.EMPTY
        }
    }

    private fun ensureLine(absRow: Int) {
        while (absRow >= lines.size) {
            lines.addLast(blankLine())
            while (lines.size > scrollbackLimit + rows) {
                lines.removeFirst()
                cursorRow = (cursorRow - 1).coerceAtLeast(0)
            }
        }
    }

    fun switchToAlternate() {
        if (alternateMode) return
        savedMain = SavedScreen(
            lines = lines.map { it.toMutableList() },
            cursorRow = cursorRow,
            cursorCol = cursorCol,
            attrs = currentAttrs,
            scrollTop = scrollTopVis,
            scrollBottom = scrollBottomVis,
        )
        lines.clear()
        repeat(rows) { lines.add(blankLine()) }
        cursorRow = 0
        cursorCol = 0
        currentAttrs = CellAttributes.DEFAULT
        scrollTopVis = 0
        scrollBottomVis = rows - 1
        alternateMode = true
        touch()
    }

    fun switchToMain() {
        if (!alternateMode) return
        val saved = savedMain ?: return
        lines.clear()
        lines.addAll(saved.lines)
        cursorRow = saved.cursorRow
        cursorCol = saved.cursorCol
        currentAttrs = saved.attrs
        scrollTopVis = saved.scrollTop
        scrollBottomVis = saved.scrollBottom
        alternateMode = false
        savedMain = null
        touch()
    }

    fun reset() {
        lines.clear()
        repeat(rows) { lines.add(blankLine()) }
        cursorRow = 0
        cursorCol = 0
        currentAttrs = CellAttributes.DEFAULT
        scrollTopVis = 0
        scrollBottomVis = rows - 1
        alternateMode = false
        savedMain = null
        cursorVisible = true
        windowTitle = ""
        touch()
    }

    fun clearAndScrollback() {
        lines.clear()
        repeat(rows) { lines.add(blankLine()) }
        cursorRow = 0
        cursorCol = 0
        touch()
    }

    /**
     * Overwrites the [rows] x [cols] visible window with a full grid produced by an external
     * VT engine (e.g. the native emulator) and repositions the cursor. The scrollback (`lines`
     * older than the visible window) is left untouched but is not extended by this path —
     * external engines that own their own grid have no concept of history here.
     */
    fun replaceVisibleGrid(
        cells: Array<TerminalCell>,
        cursorVisRow: Int,
        cursorCol: Int,
        cursorVisible: Boolean,
    ) {
        require(cells.size == rows * cols) {
            "replaceVisibleGrid expected ${rows * cols} cells, got ${cells.size}"
        }
        val top = visibleTop
        for (r in 0 until rows) {
            val line = lines[top + r]
            while (line.size < cols) line.add(TerminalCell.EMPTY)
            for (c in 0 until cols) {
                line[c] = cells[r * cols + c]
            }
            while (line.size > cols) line.removeLast()
        }
        cursorRow = top + cursorVisRow.coerceIn(0, rows - 1)
        this.cursorCol = cursorCol.coerceIn(0, cols - 1)
        this.cursorVisible = cursorVisible
        touch()
    }

    private data class SavedScreen(
        val lines: List<MutableList<TerminalCell>>,
        val cursorRow: Int,
        val cursorCol: Int,
        val attrs: CellAttributes,
        val scrollTop: Int,
        val scrollBottom: Int,
    )
}