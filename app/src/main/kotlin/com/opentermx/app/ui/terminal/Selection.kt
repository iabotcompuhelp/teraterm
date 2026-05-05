package com.opentermx.app.ui.terminal

data class CellPos(val row: Int, val col: Int) : Comparable<CellPos> {
    override fun compareTo(other: CellPos): Int {
        val r = row.compareTo(other.row)
        return if (r != 0) r else col.compareTo(other.col)
    }
}

class Selection {
    private var anchor: CellPos? = null
    private var caret: CellPos? = null

    val isActive: Boolean
        get() {
            val a = anchor; val c = caret
            return a != null && c != null && a != c
        }

    fun start(row: Int, col: Int) {
        anchor = CellPos(row, col)
        caret = CellPos(row, col)
    }

    fun extend(row: Int, col: Int) {
        caret = CellPos(row, col)
    }

    fun clear() {
        anchor = null
        caret = null
    }

    fun normalized(): Pair<CellPos, CellPos>? {
        val a = anchor ?: return null
        val c = caret ?: return null
        return if (a <= c) a to c else c to a
    }

    fun contains(row: Int, col: Int): Boolean {
        val (start, end) = normalized() ?: return false
        if (row < start.row || row > end.row) return false
        if (start.row == end.row) return col in start.col until end.col
        if (row == start.row) return col >= start.col
        if (row == end.row) return col < end.col
        return true
    }

    fun textFromBuffer(buffer: TerminalBuffer): String {
        val (start, end) = normalized() ?: return ""
        val sb = StringBuilder()
        for (row in start.row..end.row) {
            val fromCol = if (row == start.row) start.col else 0
            val toCol = if (row == end.row) end.col else buffer.cols
            val line = buffer.lineAt(row) ?: continue
            val maxCol = toCol.coerceAtMost(line.size)
            var lastNonBlank = fromCol - 1
            for (c in fromCol until maxCol) {
                if (line[c].char != ' ' || line[c].attrs != CellAttributes.DEFAULT) lastNonBlank = c
            }
            for (c in fromCol..lastNonBlank) {
                sb.append(line[c].char)
            }
            if (row != end.row) sb.append('\n')
        }
        return sb.toString()
    }
}