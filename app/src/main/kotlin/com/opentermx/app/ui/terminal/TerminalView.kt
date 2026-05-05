package com.opentermx.app.ui.terminal

import javafx.animation.AnimationTimer
import javafx.application.Platform
import javafx.beans.property.ReadOnlyStringProperty
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.geometry.Orientation
import javafx.scene.canvas.Canvas
import javafx.scene.control.ScrollBar
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.layout.BorderPane
import java.nio.charset.Charset

private val ESC = Char(0x1B).toString()
private val DEL = Char(0x7F).toString()
private val CSI = ESC + "["

class TerminalView(
    fontFamily: String = "Consolas",
    fontSize: Double = 14.0,
) : BorderPane() {

    private val canvas = Canvas(800.0, 480.0)
    private val scrollBar = ScrollBar().apply { orientation = Orientation.VERTICAL }
    private val buffer = TerminalBuffer(80, 24, scrollbackLimit = 10_000)
    private val emulator = TerminalEmulator(buffer)
    private val renderer = TerminalRenderer(fontFamily, fontSize)
    private val selection = Selection()

    private val titleWrapper = ReadOnlyStringWrapper("")
    val titleProperty: ReadOnlyStringProperty get() = titleWrapper.readOnlyProperty

    private var viewportTop: Int = 0
    private var followBottom: Boolean = true
    private var lastPaintedVersion: Long = -1
    private var dirty: Boolean = true
    private var lastTitle: String = ""

    var onInput: (String) -> Unit = {}
    var onResize: (cols: Int, rows: Int) -> Unit = { _, _ -> }

    init {
        styleClass += "terminal-area"
        center = canvas
        right = scrollBar
        canvas.isFocusTraversable = true
        focusedProperty().addListener { _, _, f -> if (f) canvas.requestFocus() }
        canvas.focusedProperty().addListener { _, _, _ -> dirty = true }

        widthProperty().addListener { _, _, _ -> updateCanvasSize() }
        heightProperty().addListener { _, _, _ -> updateCanvasSize() }
        scrollBar.widthProperty().addListener { _, _, _ -> updateCanvasSize() }

        setupKeyboard()
        setupMouse()
        setupScrollBar()
        startAnimator()
    }

    fun append(text: String) = runOnFx {
        emulator.feed(text)
        afterFeed()
    }

    fun appendBytes(data: ByteArray, length: Int, charset: Charset = Charsets.UTF_8) {
        val text = String(data, 0, length, charset)
        runOnFx {
            emulator.feed(text)
            afterFeed()
        }
    }

    fun clear() = runOnFx {
        buffer.reset()
        selection.clear()
        viewportTop = 0
        followBottom = true
        dirty = true
        updateScrollBar()
    }

    fun applyColors(foreground: javafx.scene.paint.Color, background: javafx.scene.paint.Color,
                    cursor: javafx.scene.paint.Color, selection: javafx.scene.paint.Color) = runOnFx {
        renderer.defaultFg = foreground
        renderer.defaultBg = background
        renderer.cursorColor = cursor
        renderer.selectionColor = selection
        dirty = true
    }

    fun applyFont(family: String, size: Double) = runOnFx {
        renderer.setFont(family, size)
        updateCanvasSize()
    }

    fun snapshotImage(): javafx.scene.image.Image = canvas.snapshot(null, null)

    fun exportText(): String = buildString {
        for (i in 0 until buffer.totalLines) {
            val line = buffer.lineAt(i) ?: continue
            append(line.joinToString("") { it.char.toString() }.trimEnd())
            append('\n')
        }
    }

    private fun afterFeed() {
        if (buffer.windowTitle != lastTitle) {
            lastTitle = buffer.windowTitle
            titleWrapper.value = lastTitle
        }
        if (followBottom) {
            viewportTop = (buffer.totalLines - buffer.rows).coerceAtLeast(0)
        }
        updateScrollBar()
    }

    private fun updateCanvasSize() {
        val sbw = if (scrollBar.width.isFinite()) scrollBar.width else 0.0
        canvas.width = (width - sbw).coerceAtLeast(50.0)
        canvas.height = height.coerceAtLeast(50.0)
        val newCols = renderer.colsFor(canvas.width)
        val newRows = renderer.rowsFor(canvas.height)
        if (newCols != buffer.cols || newRows != buffer.rows) {
            buffer.resize(newCols, newRows)
            if (followBottom) viewportTop = (buffer.totalLines - buffer.rows).coerceAtLeast(0)
            updateScrollBar()
            onResize(newCols, newRows)
        }
        dirty = true
    }

    private fun setupKeyboard() {
        canvas.addEventFilter(KeyEvent.KEY_TYPED) { e ->
            if (e.isShortcutDown) return@addEventFilter
            val ch = e.character
            if (ch.isNotEmpty() && ch[0].code >= 32 && ch[0].code != 127) {
                onInput(ch)
                e.consume()
            }
        }
        canvas.addEventFilter(KeyEvent.KEY_PRESSED) { e ->
            if (e.isShortcutDown && e.code == KeyCode.C) {
                if (selection.isActive) {
                    copySelection()
                    e.consume()
                    return@addEventFilter
                }
            }
            if (e.isShortcutDown && e.code == KeyCode.V) {
                pasteFromClipboard()
                e.consume()
                return@addEventFilter
            }
            if (e.isShiftDown && e.code == KeyCode.PAGE_UP) {
                scrollBy(-buffer.rows)
                e.consume()
                return@addEventFilter
            }
            if (e.isShiftDown && e.code == KeyCode.PAGE_DOWN) {
                scrollBy(buffer.rows)
                e.consume()
                return@addEventFilter
            }
            if (e.isShortcutDown && !e.isAltDown && e.code.isLetterKey()) {
                val letter = e.code.getName().firstOrNull()?.uppercaseChar()
                if (letter != null && letter in 'A'..'Z') {
                    onInput(Char(letter.code - 'A'.code + 1).toString())
                    e.consume()
                    return@addEventFilter
                }
            }
            val mapped = mapSpecialKey(e.code)
            if (mapped != null) {
                onInput(mapped)
                e.consume()
            }
        }
    }

    private fun mapSpecialKey(code: KeyCode): String? = when (code) {
        KeyCode.ENTER -> "\r"
        KeyCode.BACK_SPACE -> DEL
        KeyCode.TAB -> "\t"
        KeyCode.ESCAPE -> ESC
        KeyCode.UP -> CSI + "A"
        KeyCode.DOWN -> CSI + "B"
        KeyCode.RIGHT -> CSI + "C"
        KeyCode.LEFT -> CSI + "D"
        KeyCode.HOME -> CSI + "H"
        KeyCode.END -> CSI + "F"
        KeyCode.DELETE -> CSI + "3~"
        KeyCode.PAGE_UP -> CSI + "5~"
        KeyCode.PAGE_DOWN -> CSI + "6~"
        KeyCode.F1 -> ESC + "OP"
        KeyCode.F2 -> ESC + "OQ"
        KeyCode.F3 -> ESC + "OR"
        KeyCode.F4 -> ESC + "OS"
        else -> null
    }

    private fun setupMouse() {
        canvas.setOnMousePressed { e ->
            canvas.requestFocus()
            if (e.button == MouseButton.PRIMARY) {
                val pos = pointToCell(e.x, e.y)
                selection.start(pos.row, pos.col)
                dirty = true
            } else if (e.button == MouseButton.MIDDLE) {
                pasteFromClipboard()
            }
        }
        canvas.setOnMouseDragged { e ->
            if (e.button == MouseButton.PRIMARY) {
                val pos = pointToCell(e.x, e.y)
                selection.extend(pos.row, pos.col)
                dirty = true
            }
        }
        canvas.setOnMouseReleased { e ->
            if (e.button == MouseButton.PRIMARY && !selection.isActive) {
                selection.clear()
                dirty = true
            }
        }
        canvas.setOnScroll { e ->
            val notches = (e.deltaY / 40.0).toInt()
            val step = if (notches == 0) (if (e.deltaY > 0) -1 else 1) else -notches
            scrollBy(step * 3)
            e.consume()
        }
    }

    private fun pointToCell(x: Double, y: Double): CellPos {
        val col = (x / renderer.cellWidth).toInt().coerceIn(0, buffer.cols - 1)
        val visRow = (y / renderer.cellHeight).toInt().coerceIn(0, buffer.rows - 1)
        return CellPos(viewportTop + visRow, col)
    }

    private fun scrollBy(dRows: Int) {
        val maxTop = (buffer.totalLines - buffer.rows).coerceAtLeast(0)
        viewportTop = (viewportTop + dRows).coerceIn(0, maxTop)
        followBottom = (viewportTop == maxTop)
        updateScrollBar()
        dirty = true
    }

    private fun setupScrollBar() {
        scrollBar.min = 0.0
        scrollBar.max = 0.0
        scrollBar.unitIncrement = 1.0
        scrollBar.valueProperty().addListener { _, _, newVal ->
            val intVal = newVal.toInt()
            if (viewportTop != intVal) {
                viewportTop = intVal
                val maxTop = (buffer.totalLines - buffer.rows).coerceAtLeast(0)
                followBottom = (viewportTop == maxTop)
                dirty = true
            }
        }
    }

    private fun updateScrollBar() {
        val maxTop = (buffer.totalLines - buffer.rows).coerceAtLeast(0).toDouble()
        scrollBar.max = maxTop
        scrollBar.visibleAmount = buffer.rows.toDouble()
        scrollBar.blockIncrement = buffer.rows.toDouble()
        scrollBar.value = viewportTop.toDouble()
    }

    private fun startAnimator() {
        object : AnimationTimer() {
            override fun handle(now: Long) {
                if (dirty || buffer.version != lastPaintedVersion) {
                    renderer.paint(canvas, buffer, viewportTop, selection, canvas.isFocused)
                    lastPaintedVersion = buffer.version
                    dirty = false
                }
            }
        }.start()
    }

    fun copySelection() {
        val text = selection.textFromBuffer(buffer)
        if (text.isNotEmpty()) {
            Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(text) })
        }
    }

    fun pasteFromClipboard() {
        val cb = Clipboard.getSystemClipboard()
        if (cb.hasString()) {
            cb.string?.takeIf { it.isNotEmpty() }?.let { onInput(it) }
        }
    }

    private inline fun runOnFx(crossinline block: () -> Unit) {
        if (Platform.isFxApplicationThread()) block() else Platform.runLater { block() }
    }
}