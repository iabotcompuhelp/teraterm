package com.opentermx.app.ui.terminal

import javafx.scene.canvas.Canvas
import javafx.scene.canvas.GraphicsContext
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.FontPosture
import javafx.scene.text.FontWeight
import javafx.scene.text.Text

enum class CursorStyle { BLOCK, BAR, UNDERLINE }

class TerminalRenderer(
    var fontFamily: String = "Consolas",
    var fontSize: Double = 14.0,
    var defaultFg: Color = Color.web("#e6e6e6"),
    var defaultBg: Color = Color.web("#0f0f10"),
    var cursorColor: Color = Color.web("#e6e6e6"),
    var selectionColor: Color = Color.web("#2a4a6b88"),
    var cursorStyle: CursorStyle = CursorStyle.BLOCK,
    var cursorBlink: Boolean = true,
    var blinkTextEnabled: Boolean = true,
) {

    /**
     * Toggled by the parent view's animation timer. When `cursorBlink` is true and this is
     * false, paintCursor leaves the cell un-highlighted so the cursor appears to blink.
     */
    var cursorPhaseOn: Boolean = true

    /** Mirrors `cursorPhaseOn` but with a longer cadence for ANSI blink-attributed text. */
    var textBlinkPhaseOn: Boolean = true

    private var regularFont: Font = Font.font(fontFamily, FontWeight.NORMAL, FontPosture.REGULAR, fontSize)
    private var boldFont: Font = Font.font(fontFamily, FontWeight.BOLD, FontPosture.REGULAR, fontSize)
    private var italicFont: Font = Font.font(fontFamily, FontWeight.NORMAL, FontPosture.ITALIC, fontSize)
    private var boldItalicFont: Font = Font.font(fontFamily, FontWeight.BOLD, FontPosture.ITALIC, fontSize)

    var cellWidth: Double = 0.0
        private set
    var cellHeight: Double = 0.0
        private set
    private var ascent: Double = 0.0

    init { recomputeMetrics() }

    fun setFont(family: String, size: Double) {
        fontFamily = family
        fontSize = size
        regularFont = Font.font(family, FontWeight.NORMAL, FontPosture.REGULAR, size)
        boldFont = Font.font(family, FontWeight.BOLD, FontPosture.REGULAR, size)
        italicFont = Font.font(family, FontWeight.NORMAL, FontPosture.ITALIC, size)
        boldItalicFont = Font.font(family, FontWeight.BOLD, FontPosture.ITALIC, size)
        recomputeMetrics()
    }

    private fun recomputeMetrics() {
        val probe = Text("M").apply { font = regularFont }
        val bounds = probe.layoutBounds
        cellWidth = bounds.width
        cellHeight = bounds.height
        ascent = probe.baselineOffset
    }

    fun colsFor(width: Double): Int = (width / cellWidth).toInt().coerceAtLeast(1)
    fun rowsFor(height: Double): Int = (height / cellHeight).toInt().coerceAtLeast(1)

    fun paint(
        canvas: Canvas,
        buffer: TerminalBuffer,
        viewportTop: Int,
        selection: Selection,
        focused: Boolean,
        overlayProvider: ((absRow: Int, line: List<TerminalCell>) -> com.opentermx.app.ui.terminal.highlight.LineOverlay)? = null,
    ) {
        val gc = canvas.graphicsContext2D
        val width = canvas.width
        val height = canvas.height
        gc.fill = defaultBg
        gc.fillRect(0.0, 0.0, width, height)

        val visibleRows = (height / cellHeight).toInt() + 1
        val cols = (width / cellWidth).toInt() + 1

        for (rowOffset in 0 until visibleRows) {
            val absRow = viewportTop + rowOffset
            val line = buffer.lineAt(absRow) ?: continue
            val overlay = overlayProvider?.invoke(absRow, line)
            paintRow(gc, line, absRow, rowOffset, cols, selection, overlay)
        }

        val cursorAbs = buffer.cursorRow
        val cursorVisRow = cursorAbs - viewportTop
        if (buffer.cursorVisible && cursorVisRow in 0 until visibleRows) {
            paintCursor(gc, buffer, cursorVisRow, focused)
        }
    }

    private fun paintRow(
        gc: GraphicsContext,
        line: List<TerminalCell>,
        absRow: Int,
        visRow: Int,
        cols: Int,
        selection: Selection,
        overlay: com.opentermx.app.ui.terminal.highlight.LineOverlay?,
    ) {
        val y = visRow * cellHeight
        var col = 0
        while (col < cols) {
            val cell = line.getOrNull(col) ?: TerminalCell.EMPTY
            val attrs = cell.attrs
            val selected = selection.contains(absRow, col)
            val (baseFg, baseBg) = effectiveColors(attrs)
            val overlayRun = overlay?.colorAt(col)
            val (fg, bg) = applyOverlay(baseFg, baseBg, attrs, overlayRun)

            gc.fill = bg
            gc.fillRect(col * cellWidth, y, cellWidth, cellHeight)

            if (selected) {
                gc.fill = selectionColor
                gc.fillRect(col * cellWidth, y, cellWidth, cellHeight)
            }

            // ANSI blink (SGR 5/6): when both the user pref (blinkTextEnabled) and the cell
            // attribute say blink, the off phase paints the glyph with very low alpha so it
            // appears to flash in/out without a layout shift.
            val blinkOff = attrs.blink && blinkTextEnabled && !textBlinkPhaseOn
            if (!attrs.hidden && cell.char != ' ') {
                gc.fill = fg
                gc.font = pickFont(attrs)
                gc.globalAlpha = when {
                    blinkOff -> 0.2
                    attrs.dim -> 0.7
                    else -> 1.0
                }
                gc.fillText(cell.char.toString(), col * cellWidth, y + ascent)
                gc.globalAlpha = 1.0

                if (attrs.underline) {
                    gc.stroke = fg
                    gc.lineWidth = 1.0
                    val uy = y + cellHeight - 1.5
                    gc.strokeLine(col * cellWidth, uy, (col + 1) * cellWidth, uy)
                }
                if (attrs.strikethrough) {
                    gc.stroke = fg
                    gc.lineWidth = 1.0
                    val sy = y + cellHeight / 2.0
                    gc.strokeLine(col * cellWidth, sy, (col + 1) * cellWidth, sy)
                }
            }
            col++
        }
    }

    private fun paintCursor(gc: GraphicsContext, buffer: TerminalBuffer, visRow: Int, focused: Boolean) {
        val col = buffer.cursorCol
        val x = col * cellWidth
        val y = visRow * cellHeight
        // Off phase of a blinking cursor: paint nothing, the cell content is already drawn.
        if (cursorBlink && !cursorPhaseOn) return
        val cell = buffer.cellAt(buffer.cursorRow, col)
        val (fg, _) = effectiveColors(cell.attrs)
        if (!focused) {
            gc.stroke = cursorColor
            gc.lineWidth = 1.0
            gc.strokeRect(x + 0.5, y + 0.5, cellWidth - 1, cellHeight - 1)
            return
        }
        when (cursorStyle) {
            CursorStyle.BLOCK -> {
                gc.fill = fg
                gc.fillRect(x, y, cellWidth, cellHeight)
                if (cell.char != ' ') {
                    gc.fill = defaultBg
                    gc.font = pickFont(cell.attrs)
                    gc.fillText(cell.char.toString(), x, y + ascent)
                }
            }
            CursorStyle.BAR -> {
                gc.fill = fg
                gc.fillRect(x, y, 2.0, cellHeight)
            }
            CursorStyle.UNDERLINE -> {
                gc.fill = fg
                gc.fillRect(x, y + cellHeight - 2.0, cellWidth, 2.0)
            }
        }
    }

    private fun effectiveColors(attrs: CellAttributes): Pair<Color, Color> {
        val fg = AnsiPalette.resolve(attrs.fg, defaultFg, defaultBg, isFg = true)
        val bg = AnsiPalette.resolve(attrs.bg, defaultFg, defaultBg, isFg = false)
        return if (attrs.inverse) bg to fg else fg to bg
    }

    /**
     * Aplica el overlay del HighlightEngine respetando la merge policy. Si la celda ya tiene
     * un fg explícito del servidor (no Default) y la policy es RESPECT_SERVER, el fg del
     * server gana; si es OVERRIDE, el overlay siempre pisa. El bg del overlay aplica si está.
     */
    private fun applyOverlay(
        baseFg: Color,
        baseBg: Color,
        attrs: CellAttributes,
        run: com.opentermx.app.ui.terminal.highlight.OverlayRun?,
    ): Pair<Color, Color> {
        if (run == null) return baseFg to baseBg
        val serverHasExplicitFg = attrs.fg != AnsiColor.Default
        val applyFg = when (run.mergePolicy) {
            com.opentermx.app.ui.terminal.highlight.MergePolicy.OVERRIDE -> true
            com.opentermx.app.ui.terminal.highlight.MergePolicy.RESPECT_SERVER -> !serverHasExplicitFg
            com.opentermx.app.ui.terminal.highlight.MergePolicy.MERGE -> false
        }
        val overlayFg = if (applyFg) AnsiPalette.resolve(run.fg, defaultFg, defaultBg, isFg = true) else baseFg
        val overlayBg = if (run.bg != null) {
            AnsiPalette.resolve(run.bg, defaultFg, defaultBg, isFg = false)
        } else baseBg
        return overlayFg to overlayBg
    }

    private fun pickFont(attrs: CellAttributes): Font = when {
        attrs.bold && attrs.italic -> boldItalicFont
        attrs.bold -> boldFont
        attrs.italic -> italicFont
        else -> regularFont
    }
}