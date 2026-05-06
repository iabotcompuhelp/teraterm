package com.opentermx.app.ui.terminal

sealed interface AnsiColor {
    data object Default : AnsiColor
    data class Indexed(val index: Int) : AnsiColor
    data class Rgb(val r: Int, val g: Int, val b: Int) : AnsiColor
}

data class CellAttributes(
    val fg: AnsiColor = AnsiColor.Default,
    val bg: AnsiColor = AnsiColor.Default,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
    val hidden: Boolean = false,
    val strikethrough: Boolean = false,
    val blink: Boolean = false,
) {
    companion object {
        val DEFAULT = CellAttributes()
    }
}

data class TerminalCell(
    val char: Char = ' ',
    val attrs: CellAttributes = CellAttributes.DEFAULT,
) {
    companion object {
        val EMPTY = TerminalCell()
    }
}