package com.opentermx.app.ui.terminal

import com.opentermx.serial.SerialConnectionFactory
import com.opentermx.serial.nativeio.NativeCell
import com.opentermx.serial.nativeio.NativeTerminal
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
import org.slf4j.LoggerFactory
import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException

/** Motor VT que la vista usa para interpretar la entrada. */
enum class TerminalEngine { KOTLIN, NATIVE }

private val ESC = Char(0x1B).toString()
private val DEL = Char(0x7F).toString()
private val BS = Char(0x08).toString()
private val CSI = ESC + "["
private const val CURSOR_BLINK_PERIOD_NS = 500_000_000L
private const val TEXT_BLINK_PERIOD_NS = 800_000_000L
private const val SMOOTH_SCROLL_STEP_NS = 16_000_000L

/**
 * Filas de margen entre la fila del cursor (prompt) y el borde inferior del canvas
 * cuando `followBottom` está activo. Sin este margen, el prompt queda pegado al borde
 * inferior de la ventana, ergonómicamente incómodo. Equivale al `scrolloff` de Vim
 * aplicado solo al fondo.
 */
private const val BOTTOM_PADDING_ROWS = 3

/** Valor centinela `OPENTERMX_COLOR_DEFAULT` en native/src/terminal_emu.h. */
private const val NATIVE_COLOR_DEFAULT: Int = -0x1   // 0xFFFFFFFF como int con signo

private val log = LoggerFactory.getLogger(TerminalView::class.java)

class TerminalView(
    fontFamily: String = "Consolas",
    fontSize: Double = 14.0,
    scrollbackLimit: Int = 10_000,
    initialCols: Int = 80,
    initialRows: Int = 24,
    engine: TerminalEngine = TerminalEngine.KOTLIN,
) : BorderPane() {

    // Canvas con prefSize = 0 para que NO contribuya al prefSize del BorderPane
    // contenedor. Sin esto, `canvas.getHeight()` (= prefHeight default) alimenta el
    // prefHeight del BorderPane → el padre crece 2px por pulse → updateCanvasSize
    // setea canvas.height más grande → loop infinito que llena el buffer de blanks
    // y deja al usuario sin scrollback útil. Ver `[scroll-diag]` instrumentation.
    private val canvas = object : Canvas(800.0, 480.0) {
        override fun prefWidth(height: Double): Double = 0.0
        override fun prefHeight(width: Double): Double = 0.0
        override fun minWidth(height: Double): Double = 0.0
        override fun minHeight(width: Double): Double = 0.0
        override fun maxWidth(height: Double): Double = Double.MAX_VALUE
        override fun maxHeight(width: Double): Double = Double.MAX_VALUE
    }
    private val scrollBar = ScrollBar().apply { orientation = Orientation.VERTICAL }
    private val buffer = TerminalBuffer(initialCols, initialRows, scrollbackLimit = scrollbackLimit)
    private val emulator = TerminalEmulator(buffer)
    private val renderer = TerminalRenderer(fontFamily, fontSize)
    private val selection = Selection()

    /**
     * El motor efectivo: aunque el constructor pida NATIVE, si la librería nativa no carga
     * caemos a KOTLIN con un warning. El campo refleja lo que está realmente en uso.
     */
    val engine: TerminalEngine
    private var nativeTerminal: NativeTerminal? = null

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

    /**
     * Engine de resaltado contextual. Construido lazy con un settings provider que devuelve
     * `HighlightSettings()` por default (toggle ON). `MainWindow` puede sustituir el provider
     * con `setHighlightSettingsProvider(...)` para enchufar la config persistida del usuario.
     */
    private var highlightSettingsProvider: () -> com.opentermx.app.ui.terminal.highlight.HighlightSettings =
        { com.opentermx.app.ui.terminal.highlight.HighlightSettings() }
    private val highlightEngine by lazy {
        com.opentermx.app.ui.terminal.highlight.HighlightEngine(
            settingsProvider = { highlightSettingsProvider() },
        )
    }
    private val highlightOverlayProvider: (Int, List<TerminalCell>) -> com.opentermx.app.ui.terminal.highlight.LineOverlay =
        { absRow, line ->
            highlightEngine.overlayFor(
                absRow = absRow,
                line = line,
                isCursorRow = absRow == buffer.cursorRow,
                inAlternateScreen = buffer.alternateMode,
            )
        }

    fun setHighlightSettingsProvider(provider: () -> com.opentermx.app.ui.terminal.highlight.HighlightSettings) {
        this.highlightSettingsProvider = provider
        highlightEngine.invalidateCache()
        dirty = true
    }

    /**
     * Devuelve las últimas [count] líneas plano (sin atributos) del buffer. Usado por
     * el AI Assistant (contexto del terminal) y por el endpoint REST `GET /api/terminal/buffer`.
     */
    fun snapshotLastLines(count: Int): List<String> = buffer.snapshotLastLines(count)

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

        this.engine = initNativeEngine(engine, initialCols, initialRows)

        setupKeyboard()
        setupMouse()
        setupScrollBar()
        startAnimator()
    }

    /**
     * Intenta levantar el emulador VT nativo si lo piden y la librería carga. En cualquier
     * fallo deja `nativeTerminal = null` y devuelve KOTLIN, para que el resto de la vista
     * use el motor Kotlin sin que el usuario tenga que reaccionar.
     */
    private fun initNativeEngine(
        requested: TerminalEngine,
        cols: Int,
        rows: Int,
    ): TerminalEngine {
        if (requested != TerminalEngine.NATIVE) return TerminalEngine.KOTLIN
        if (!SerialConnectionFactory.isNativeAvailable()) {
            log.warn("Motor VT nativo solicitado pero opentermx_native no carga; usando motor Kotlin")
            return TerminalEngine.KOTLIN
        }
        return try {
            val nt = NativeTerminal.create(rows, cols)
            nt.setWriteCallback { reply ->
                if (reply.isEmpty()) return@setWriteCallback
                // Las respuestas del emulador (DA, CPR, …) siempre van en bytes VT/ASCII,
                // pero el `charset` actual gobierna cómo onInput las codifica de vuelta al transporte.
                val text = String(reply, charset)
                Platform.runLater { onInput(text) }
            }
            nativeTerminal = nt
            syncFromNative()
            TerminalEngine.NATIVE
        } catch (t: Throwable) {
            log.warn("No se pudo crear NativeTerminal ({}); usando motor Kotlin", t.toString())
            TerminalEngine.KOTLIN
        }
    }

    fun append(text: String) = runOnFx {
        val nt = nativeTerminal
        if (nt != null) {
            val bytes = text.toByteArray(charset)
            feedNative(nt, bytes, bytes.size)
        } else {
            emulator.feed(text)
        }
        afterFeed()
    }

    fun appendBytes(data: ByteArray, length: Int, cs: Charset = charset) {
        // CRITICAL: `data` es un buffer compartido reusado por el reader del SSH/Telnet
        // (`SshConnection.readLoop` declara `byte[] buf` UNA sola vez fuera del while
        // y llama `h.onData(buf, n)` con la misma referencia en cada iteración).
        //
        // Si queueamos `runOnFx { emulator.feed(String(data, ..., cs)) }`, la decodificación
        // ocurre en el FX thread DESPUÉS de que el reader ya hizo otro `read(buf)` y
        // sobreescribió los bytes — el FX termina procesando los bytes del chunk SIGUIENTE.
        // Síntoma observado: el segundo `\r\n` dentro de un chunk de 40 bytes
        // (prompt + \r\n + prompt) se procesaba pero el primer chunk (`\r\n` de 2 bytes)
        // veía bytes del chunk 2 → faltaba un lineFeed → dos prompts apilados en la
        // misma fila ([lf-trace] mostraba solo 1 lineFeed por Enter en vez de 2).
        //
        // Fix: decodificar/copiar AHORA, en el reader thread, antes del runOnFx.
        // `String(data, 0, length, cs)` hace una copia interna (chars), `ByteArray.copyOfRange`
        // hace una copia explícita. Ambos snapshots son seguros pasarlos al FX thread.
        val text = if (nativeTerminal == null) String(data, 0, length, cs) else ""
        val nativeBytes = if (nativeTerminal != null) data.copyOfRange(0, length) else null
        runOnFx {
            val nt = nativeTerminal
            if (nt != null && nativeBytes != null) {
                feedNative(nt, transcodeForNative(nativeBytes, nativeBytes.size, cs))
            } else {
                emulator.feed(text)
            }
            afterFeed()
        }
    }

    fun clear() = runOnFx {
        nativeTerminal?.reset()
        buffer.reset()
        selection.clear()
        viewportTop = 0
        followBottom = true
        dirty = true
        if (nativeTerminal != null) syncFromNative()
        updateScrollBar()
    }

    /**
     * Libera el emulador nativo si la vista lo abrió. Llamar al cerrar la pestaña que la contiene
     * para no dejar el handle JNA colgado durante toda la vida de la JVM.
     */
    fun dispose() = runOnFx {
        nativeTerminal?.close()
        nativeTerminal = null
    }

    private fun feedNative(nt: NativeTerminal, bytes: ByteArray, length: Int = bytes.size) {
        nt.feed(bytes, length)
        syncFromNative()
    }

    /** Convierte el bloque entrante al UTF-8 que espera el emulador nativo. */
    private fun transcodeForNative(data: ByteArray, length: Int, cs: Charset): ByteArray {
        if (cs == Charsets.UTF_8) {
            return if (length == data.size) data else data.copyOfRange(0, length)
        }
        return String(data, 0, length, cs).toByteArray(Charsets.UTF_8)
    }

    /**
     * Vuelca la snapshot del emulador nativo en el TerminalBuffer (visible window) y
     * reposiciona el cursor. El renderer y la lógica de scroll/select existentes pintan
     * sin enterarse de qué motor parseó la entrada.
     *
     * Nota: la librería nativa no mantiene historial — sólo el grid visible. En modo NATIVE
     * el scrollback que la vista conserva sólo se rellena con lo que viva en el viewport.
     */
    private fun syncFromNative() {
        val nt = nativeTerminal ?: return
        val nRows = nt.rows()
        val nCols = nt.cols()
        if (nRows <= 0 || nCols <= 0) return
        if (buffer.cols != nCols || buffer.rows != nRows) {
            buffer.resize(nCols, nRows)
        }
        val raw = nt.snapshot()
        val mapped = Array(nRows * nCols) { TerminalCell.EMPTY }
        for (i in 0 until nRows * nCols) {
            mapped[i] = nativeCellToTerminalCell(raw[i])
        }
        val cur = nt.cursor()
        buffer.replaceVisibleGrid(
            cells = mapped,
            cursorVisRow = cur.row,
            cursorCol = cur.col,
            cursorVisible = cur.visible.toInt() != 0,
        )
    }

    private fun nativeCellToTerminalCell(nc: NativeCell): TerminalCell {
        val cp = nc.codepoint
        val ch = if (cp <= 0 || cp > 0xFFFF) ' ' else cp.toChar()
        val a = nc.attrs.toInt() and 0xFFFF
        return TerminalCell(
            char = ch,
            attrs = CellAttributes(
                fg = nativeColor(nc.fgRgb),
                bg = nativeColor(nc.bgRgb),
                bold = a and 0x01 != 0,
                dim = a and 0x02 != 0,
                italic = a and 0x04 != 0,
                underline = a and 0x08 != 0,
                blink = a and 0x10 != 0,
                inverse = a and 0x20 != 0,
                hidden = a and 0x40 != 0,
                strikethrough = a and 0x80 != 0,
            ),
        )
    }

    private fun nativeColor(rgb: Int): AnsiColor =
        if (rgb == NATIVE_COLOR_DEFAULT) AnsiColor.Default
        else AnsiColor.Rgb((rgb ushr 16) and 0xFF, (rgb ushr 8) and 0xFF, rgb and 0xFF)

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

    /**
     * Top del viewport cuando `followBottom` está activo. Suma hasta `BOTTOM_PADDING_ROWS`
     * al `totalLines - rows` clásico para que el cursor (prompt) quede unas filas por encima
     * del borde inferior del canvas, con espacio inexistente debajo (renderer las pinta
     * como bg vacío).
     *
     * El padding crece gradualmente con el scrollback: sin scrollback no se aplica (sino
     * el cursor terminaría por encima del viewport y desaparecería); con scrollback >= 3
     * llega al tope de `BOTTOM_PADDING_ROWS`.
     */
    private fun followBottomTarget(): Int {
        val plain = (buffer.totalLines - buffer.rows).coerceAtLeast(0)
        return plain + BOTTOM_PADDING_ROWS.coerceAtMost(plain)
    }

    private fun afterFeed() {
        if (buffer.windowTitle != lastTitle) {
            lastTitle = buffer.windowTitle
            titleWrapper.value = lastTitle
        }
        if (followBottom) {
            val target = followBottomTarget()
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
            // El motor nativo es dueño del grid: redimensiona primero allí, luego en el buffer
            // (que el sync sólo vuelca sobre la ventana visible).
            nativeTerminal?.resize(newRows, newCols)
            buffer.resize(newCols, newRows)
            if (nativeTerminal != null) syncFromNative()
            if (followBottom) viewportTop = followBottomTarget()
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
        // UX estándar de terminales: si el operador estaba scrolleado arriba (followBottom=false)
        // y empieza a tipear, asumimos que quiere ver el prompt y la salida del próximo comando.
        // Snap al fondo y re-enganchamos el follow para que la output que llegue sea visible.
        if (!followBottom) {
            followBottom = true
            viewportTop = followBottomTarget()
            smoothTargetTop = viewportTop
            updateScrollBar()
            dirty = true
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
            // Consumir para que el TabPane padre no reciba el press y se
            // robe el focus en su skin interno — los KeyEvent dejaban de
            // llegar al canvas (ver [focus-diag]).
            e.consume()
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
        val maxTop = followBottomTarget()
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
                val maxTop = followBottomTarget()
                viewportTop = intVal
                followBottom = (viewportTop == maxTop)
                dirty = true
            }
        }
    }

    private fun updateScrollBar() {
        val maxTop = followBottomTarget().toDouble()
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
                // No pintar hasta que el Canvas esté en una Scene con Window mostrada.
                // Emitir draw commands antes de eso deja NGCanvas con cola pendiente pero
                // sin RTTexture válida; en la primera renderForcedContent que dispara la
                // pulse al adjuntar la scene, RTTexture.createGraphics() devuelve null y
                // se levanta NPE en NGCanvas$RenderBuf.validate.
                if (canvas.scene?.window?.isShowing != true) return
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
                    renderer.paint(
                        canvas, buffer, viewportTop, selection, canvas.isFocused,
                        overlayProvider = highlightOverlayProvider,
                    )
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