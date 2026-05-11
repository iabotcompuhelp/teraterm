package com.opentermx.app.ui.macro

import com.opentermx.app.viewmodel.TerminalSessionController
import com.opentermx.macro.MacroAiBridge
import com.opentermx.macro.MacroEngine
import com.opentermx.macro.MacroExecution
import com.opentermx.macro.MacroLogEntry
import javafx.application.Platform
import javafx.geometry.Orientation
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.control.TextArea
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import javafx.stage.Window
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.LineNumberFactory
import java.io.File

class MacroPanel(
    private val activeSessions: () -> List<TerminalSessionController>,
    private val aiBridgeProvider: () -> MacroAiBridge = { MacroAiBridge.NoOp() },
) : BorderPane() {

    private val engine = MacroEngine()
    private val bridge = MacroUiBridgeImpl()

    private val sessionCombo = ComboBox<TerminalSessionController>().apply {
        cellFactory = javafx.util.Callback {
            object : javafx.scene.control.ListCell<TerminalSessionController>() {
                override fun updateItem(item: TerminalSessionController?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) "" else item.session.name
                }
            }
        }
        buttonCell = cellFactory.call(null)
    }

    private val codeArea = CodeArea().apply {
        styleClass += "code-area"
        paragraphGraphicFactory = LineNumberFactory.get(this)
        replaceText(DEFAULT_SCRIPT)
        textProperty().addListener { _, _, newText ->
            setStyleSpans(0, GroovyHighlighter.highlight(newText))
        }
        setStyleSpans(0, GroovyHighlighter.highlight(text))
    }

    private val logArea = TextArea().apply {
        isEditable = false
        styleClass += "macro-log"
        promptText = "Log de ejecución"
    }

    private val statusLabel = Label("Inactivo")
    private val runButton = Button("Ejecutar").apply { setOnAction { runMacro() } }
    private val cancelButton = Button("Cancelar").apply {
        isDisable = true
        setOnAction { currentExecution?.cancel() }
    }
    private val openButton = Button("Abrir…").apply { setOnAction { openFile() } }
    private val saveButton = Button("Guardar…").apply { setOnAction { saveFile() } }
    private val clearLogButton = Button("Limpiar log").apply { setOnAction { logArea.clear() } }

    private var currentExecution: MacroExecution? = null
    private var pollThread: Thread? = null

    init {
        refreshSessions()
        sessionCombo.maxWidth = 240.0

        val toolbar = HBox(
            8.0,
            Label("Sesión:"), sessionCombo,
            Region().also { HBox.setHgrow(it, Priority.ALWAYS) },
            openButton, saveButton, clearLogButton,
            Region().also { it.prefWidth = 12.0 },
            runButton, cancelButton,
        ).apply { styleClass += "macro-toolbar" }

        val split = SplitPane(codeArea, logArea).apply {
            orientation = Orientation.VERTICAL
            setDividerPositions(0.65)
        }
        VBox.setVgrow(split, Priority.ALWAYS)

        val statusBar = HBox(8.0, statusLabel).apply { styleClass += "status-bar" }
        top = toolbar
        center = split
        bottom = statusBar
    }

    fun refreshSessions() {
        val sessions = activeSessions()
        val previous = sessionCombo.value
        sessionCombo.items.setAll(sessions)
        sessionCombo.value = sessions.firstOrNull { it === previous } ?: sessions.firstOrNull()
    }

    private fun runMacro() {
        val target = sessionCombo.value
        val script = codeArea.text
        currentExecution?.cancel()

        runButton.isDisable = true
        cancelButton.isDisable = false
        statusLabel.text = if (target != null) "Ejecutando contra ${target.session.name}…" else "Ejecutando (sin sesión)…"
        appendLog("--- inicio ---")

        val exec = engine.start(
            script,
            target?.session?.connection,
            target?.session?.id?.value,
            bridge,
            aiBridgeProvider(),
        ) { entry -> Platform.runLater { appendLog(formatEntry(entry)) } }

        currentExecution = exec
        pollThread = Thread({
            try { exec.await() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            val result = exec.getResult()
            Platform.runLater {
                runButton.isDisable = false
                cancelButton.isDisable = true
                if (result == null) {
                    statusLabel.text = "Cancelado"
                } else if (result.success()) {
                    statusLabel.text = "OK (${result.log().size} pasos)"
                } else {
                    val err = result.error()
                    statusLabel.text = "Error: ${err?.message ?: err?.javaClass?.simpleName}"
                    appendLog("[error] ${err?.message ?: err}")
                }
                appendLog("--- fin ---")
                currentExecution = null
            }
        }, "macro-poller").apply { isDaemon = true }
        pollThread?.start()
    }

    private fun formatEntry(entry: MacroLogEntry): String {
        val ms = entry.elapsedMillis()
        return "[%6d ms] %s".format(ms, entry.message())
    }

    private fun appendLog(line: String) {
        logArea.appendText(line + "\n")
    }

    private fun openFile() {
        val chooser = FileChooser().apply {
            title = "Abrir macro"
            extensionFilters.add(FileChooser.ExtensionFilter("Macros Groovy", "*.groovy", "*.ttl", "*.txt"))
        }
        val file = chooser.showOpenDialog(window()) ?: return
        codeArea.replaceText(file.readText())
    }

    private fun saveFile() {
        val chooser = FileChooser().apply {
            title = "Guardar macro"
            extensionFilters.add(FileChooser.ExtensionFilter("Macros Groovy", "*.groovy"))
            initialFileName = "macro.groovy"
        }
        val file: File = chooser.showSaveDialog(window()) ?: return
        file.writeText(codeArea.text)
    }

    private fun window(): Window? = scene?.window

    fun stylesheets(): String? = javaClass.classLoader.getResource("css/macro-editor.css")?.toExternalForm()

    companion object {
        private val DEFAULT_SCRIPT = """
            // Macro de ejemplo: login automático
            // sendln "user"
            // waitfor "Password:"
            // sendln "secret"
            // waitfor "$ "
            // messagebox "Sesión lista"

            log "macro de ejemplo - edita y pulsa Ejecutar"
            pause 200
            log "fin"
        """.trimIndent()
    }
}