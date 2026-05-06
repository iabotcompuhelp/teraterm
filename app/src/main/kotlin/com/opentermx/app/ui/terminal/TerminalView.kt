package com.opentermx.app.ui.terminal

import javafx.animation.AnimationTimer
import javafx.application.Platform
import javafx.beans.property.ReadOnlyStringProperty
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.geometry.Orientation
import javafx.scene.Cursor
import javafx.scene.canvas.Canvas
import javafx.scene.control.ScrollBar
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.layout.BorderPane
import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException

private val ESC = Char(0x1B).toString()
private val DEL = Char(0x7F).toString()
private val BS = Char(0x08).toString()
private val CSI = ESC + "["
private const val CURSOR_BLINK_PERIOD_NS = 500_000_000L
private const val TEXT_BLINK_PERIOD_NS = 800_000_000L
private const val SMOOTH_SCROLL_STEP_NS = 16_000_000L

class TerminalView(
    fontFamily: String = "Consolas",
    fontSize: Double = 14.0,
    scrollbackLimit: Int = 10_000,
    initialCols: Int = 80,
    initialRows: Int = 24,
) : BorderPane() {

    private val canvas = Canvas(800.0, 480.0)
    private val scrollBar = ScrollBar().apply { orientation = Orientation.VERTICAL }
    private val buffer = TerminalBuffer(initialCols, initialRows, scrollbackLimit = scrollbackLimit)
    private val emulator = TerminalEmulator(buffer)
    private val renderer = TerminalRenderer(fontFamily, fontSize)
    private val selection = Selection()

    private var charset: Charset = Charsets.UTF_8
    private var newlineSequence: String = "\r"
    private var localEcho: Boolean = false
    private var copyOnSelect: Boolean = false
    private var cursorBlinkRequested: Boolean = true
    private var cursorBlinkAllowed: Boolean = true
    private var smoothScroll: Boolean = false
    private var smoothTargetTop: Int = 0
    private var backspaceSendsDel: Boolean = true
    private var deleteSendsBs: Boolean = false
    private var metaSendsEscape: Boolean = true

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

    fun appendBytes(data: ByteArray, length: Int, cs: Charset = charset) {
        val text = String(data, 0, length, cs)
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

    fun applyScrollbackLimit(limit: Int) = runOnFx {
        buffer.scrollbackLimit = limit
        dirty = true
    }

    /**
     * Applies the per-terminal behavioural settings (cursor style/blink, encoding, newline
     * mode, local echo). cols/rows are honoured only on construction — the running viewport
     * follows the canvas size.
     */
    fun applyTerminalSettings(
        cursorStyle: String = "BLOCK",
        cursorBlink: Boolean = true,
        encoding: String = "UTF-8",
        newlineMode: String = "CR",
        localEcho: Boolean = false,
        scrollMode: String = "JUMP",
    ) = runOnFx {
        renderer.cursorStyle = parseCursorStyle(cursorStyle)
        cursorBlinkRequested = cursorBlink
        applyEffectiveCursorBlink()
        charset = resolveCharset(encoding)
        newlineSequence = parseNewline(newlineMode)
        this.localEcho = localEcho
        smoothScroll = scrollMode.equals("SMOOTH", ignoreCase = true)
        if (!smoothScroll) {
            // Drop any pending interpolation so the next paint matches the current top exactly.
            smoothTargetTop = viewportTop
        }
        dirty = true
    }

    /**
     * VT-keyboard behaviour from Setup → Keyboard…. Backspace transmits BS or DEL; Delete
     * transmits BS or ESC[3~; Alt-as-Meta either prefixes ESC to the keystroke (xterm) or is
     * left to the KEY_TYPED handler so the OS can produce diacritics.
     */
    fun applyKeyboardSettings(
        backspaceSendsDel: Boolean = true,
        deleteSendsBs: Boolean = false,
        metaSendsEscape: Boolean = true,
    ) = runOnFx {
        this.backspaceSendsDel = backspaceSendsDel
        this.deleteSendsBs = deleteSendsBs
        this.metaSendsEscape = metaSendsEscape
    }

    /**
     * Hooks for AdditionalSettings (Setup → Additional…). copyOnSelect auto-copies when the user
     * releases the mouse over a non-empty selection; visualCursorBlink is a global override that
     * suppresses the cursor blink even when the per-terminal setting requests it.
     */
    fun applyAdditionalSettings(copyOnSelect: Boolean, visualCursorBlink: Boolean,
                                 blinkText: Boolean = true) = runOnFx {
        this.copyOnSelect = copyOnSelect
        cursorBlinkAllowed = visualCursorBlink
        applyEffectiveCursorBlink()
        renderer.blinkTextEnabled = blinkText
        if (!blinkText) renderer.textBlinkPhaseOn = true
        dirty = true
    }

    fun applyMouseCursor(mode: String) = runOnFx {
        canvas.cursor = when (mode.uppercase()) {
            "TEXT" -> Cursor.TEXT
            "NONE" -> Cursor.NONE
            else -> Cursor.DEFAULT
        }
    }

    private fun applyEffectiveCursorBlink() {
        val effective = cursorBlinkRequested && cursorBlinkAllowed
        renderer.cursorBlink = effective
        if (!effective) renderer.cursorPhaseOn = true
        dirty = true
    }

    private fun parseCursorStyle(value: String): CursorStyle = when (value.uppercase()) {
        "BAR" -> CursorStyle.BAR
        "UNDERLINE" -> CursorStyle.UNDERLINE
        else -> CursorStyle.BLOCK
    }

    private fun parseNewline(value: String): String = when (value.uppercase()) {
        "LF" -> "\n"
        "CRLF" -> "\r\n"
        else -> "\r"
    }

    private fun resolveCharset(name: String): Charset = try {
        Charset.forName(name)
    } catch (_: IllegalCharsetNameException) {
        Charsets.UTF_8
    } catch (_: UnsupportedCharsetException) {
        Charsets.UTF_8
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
            val target = (buffer.totalLines - buffer.rows).coerceAtLeast(0)
            if (smoothScroll) {
                smoothTargetTop = target
            } else {
                viewportTop = target
                smoothTargetTop = target
            }
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

    /**
     * Routes a user-generated input. With local echo enabled the text is also fed into the
     * emulator so the user sees what they typed even when the remote is silent.
     */
    private fun emitInput(text: String) {
        if (localEcho) {
            emulator.feed(text)
            afterFeed()
        }
        onInput(text)
    }

    private fun setupKeyboard() {
        canvas.addEventFilter(KeyEvent.KEY_TYPED) { e ->
            if (e.isShortcutDown) return@addEventFilter
            // When meta-as-escape is on, Alt+key is emitted as ESC+key from KEY_PRESSED, so
            // suppress the KEY_TYPED for the same keystroke to avoid double emission.
            if (metaSendsEscape && e.isAltDown) return@addEventFilter
            val ch = e.character
            if (ch.isNotEmpty() && ch[0].code >= 32 && ch[0].code != 127) {
                emitInput(ch)
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
                    emitInput(Char(letter.code - 'A'.code + 1).toString())
                    e.consume()
                    return@addEventFilter
                }
            }
            if (metaSendsEscape && e.isAltDown && !e.isShortcutDown && !e.isMetaDown
                && (e.code.isLetterKey() || e.code.isDigitKey())) {
                val name = e.code.getName().firstOrNull()
                if (name != null) {
                    val ch = if (e.code.isLetterKey() && !e.isShiftDown) name.lowercaseChar() else name
                    emitInput(ESC + ch)
                    e.consume()
                    return@addEventFilter
                }
            }
            val mapped = mapSpecialKey(e.code)
            if (mapped != null) {
                emitInput(mapped)
                e.consume()
            }
        }
    }

    private fun mapSpecialKey(code: KeyCode): String? = when (code) {
        KeyCode.ENTER -> newlineSequence
        KeyCode.BACK_SPACE -> if (backspaceSendsDel) DEL else BS
        KeyCode.TAB -> "\t"
        KeyCode.ESCAPE -> ESC
        KeyCode.UP -> CSI + "A"
        KeyCode.DOWN -> CSI + "B"
        KeyCode.RIGHT -> CSI + "C"
        KeyCode.LEFT -> CSI + "D"
        KeyCode.HOME -> CSI + "H"
        KeyCode.END -> CSI + "F"
        KeyCode.DELETE -> if (deleteSendsBs) BS else CSI + "3~"
        KeyCode.PAGE_UP -> CSI + "5~"
        KeyCode.PAGE_DOWN -> CSI + "6~"
        KeyCode.F1 -> ESC + "OP"
        KeyCode.F2 -> ESC + "OQ"
        KeyCode.F3 -> ESC + "OR"
        KeyCode.F4 -> ESC + "OS"
        KeyCode.F5 -> CSI + "15~"
        KeyCode.F6 -> CSI + "17~"
        KeyCode.F7 -> CSI + "18~"
        KeyCode.F8 -> CSI + "19~"
        KeyCode.F9 -> CSI + "20~"
        KeyCode.F10 -> CSI + "21~"
        KeyCode.F11 -> CSI + "23~"
        KeyCode.F12 -> CSI + "24~"
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
            if (e.button == MouseButton.PRIMARY) {
                if (selection.isActive && copyOnSelect) {
                    copySelection()
                } else if (!selection.isActive) {
                    selection.clear()
                    dirty = true
                }
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
            private var lastCursorToggle: Long = 0L
            private var lastTextToggle: Long = 0L
            private var lastSmoothStep: Long = 0L
            override fun handle(now: Long) {
                if (renderer.cursorBlink && now - lastCursorToggle >= CURSOR_BLINK_PERIOD_NS) {
                    renderer.cursorPhaseOn = !renderer.cursorPhaseOn
                    lastCursorToggle = now
                    dirty = true
                }
                if (renderer.blinkTextEnabled && now - lastTextToggle >= TEXT_BLINK_PERIOD_NS) {
                    renderer.textBlinkPhaseOn = !renderer.textBlinkPhaseOn
                    lastTextToggle = now
                    dirty = true
                }
                if (smoothScroll && smoothTargetTop != viewportTop
                        && now - lastSmoothStep >= SMOOTH_SCROLL_STEP_NS) {
                    val delta = smoothTargetTop - viewportTop
                    // Move at most one row per frame for very small gaps; for larger jumps,
                    // ramp roughly proportional to how far behind we are so we don't stall.
                    val step = when {
                        delta == 0 -> 0
                        kotlin.math.abs(delta) <= 2 -> delta
                        else -> delta / 4 + if (delta > 0) 1 else -1
                    }
                    viewportTop += step
                    lastSmoothStep = now
                    updateScrollBar()
                    dirty = true
                }
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
            cb.string?.takeIf { it.isNotEmpty() }?.let { emitInput(it) }
        }
    }

    private inline fun runOnFx(crossinline block: () -> Unit) {
        if (Platform.isFxApplicationThread()) block() else Platform.runLater { block() }
    }
}