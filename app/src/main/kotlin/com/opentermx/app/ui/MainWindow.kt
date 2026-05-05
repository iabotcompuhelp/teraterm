package com.opentermx.app.ui

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.AppSettings
import com.opentermx.app.settings.SettingsStore
import com.opentermx.app.ui.dialog.FontConfigDialog
import com.opentermx.app.ui.dialog.LogConfigDialog
import com.opentermx.app.ui.dialog.SerialConfigDialog
import com.opentermx.app.ui.dialog.SshConfigDialog
import com.opentermx.app.ui.dialog.TcpRawConfigDialog
import com.opentermx.app.ui.dialog.TelnetConfigDialog
import com.opentermx.app.ui.macro.MacroWindow
import com.opentermx.app.ui.terminal.TerminalCapture
import com.opentermx.app.ui.terminal.TerminalView
import com.opentermx.app.ui.transfer.TransferProgressDialog
import com.opentermx.app.viewmodel.AppViewModel
import com.opentermx.app.viewmodel.TerminalSessionController
import com.opentermx.app.viewmodel.TransferController
import com.opentermx.app.viewmodel.TransferProtocol
import com.opentermx.common.connection.Connection
import com.opentermx.common.connection.ConnectionConfig
import com.opentermx.common.connection.ConnectionState
import com.opentermx.common.session.Session
import com.opentermx.common.session.SessionId
import com.opentermx.logger.LogManager
import com.opentermx.serial.SerialConnection
import com.opentermx.ssh.SshConnection
 import com.opentermx.telnet.RawTcpConnection
import com.opentermx.telnet.TelnetConnection
import com.opentermx.transfer.TransferDirection
import javafx.beans.binding.Bindings
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.CheckMenuItem
import javafx.scene.control.Label
import javafx.scene.control.Menu
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuItem
import javafx.scene.control.RadioMenuItem
import javafx.scene.control.Separator
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.ToggleGroup
import javafx.scene.input.KeyCombination
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.stage.DirectoryChooser
import javafx.stage.FileChooser
import javafx.stage.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class MainWindow(
    private val stage: Stage,
    private val viewModel: AppViewModel,
    initialSettings: AppSettings,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private var settings: AppSettings = initialSettings
    private val theme = Theme(parseTheme(initialSettings.theme))

    private val tabPane = TabPane().apply { tabClosingPolicy = TabPane.TabClosingPolicy.ALL_TABS }
    private val statusLabel = Label()
    private val protocolLabel = Label()
    private val themeLabel = Label()

    private val controllers: MutableMap<Tab, TerminalSessionController> = mutableMapOf()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val macroWindow by lazy { MacroWindow(stage) { controllers.values.toList() } }

    private lateinit var rootPane: BorderPane

    fun show() {
        rootPane = BorderPane().apply {
            top = buildMenuBar()
            center = tabPane
            bottom = buildStatusBar()
            left = SessionListView(viewModel).also { it.prefWidth = 220.0 }
        }
        val scene = Scene(rootPane, 1100.0, 720.0)
        theme.applyTo(scene)
        refreshLabels()

        tabPane.selectionModel.selectedItemProperty().addListener { _, _, newTab ->
            bindStatusToTab(newTab)
        }

        stage.title = "OpenTermX"
        stage.scene = scene
        stage.setOnCloseRequest { stopAllControllers() }
        stage.show()

        openWelcomeTab()
    }

    private fun buildMenuBar(): MenuBar {
        val file = Menu(Strings["menu.file"]).apply {
            items += MenuItem(Strings["file.newSession"]).apply {
                accelerator = accelerator("file.newSession")
                setOnAction { openWelcomeTab() }
            }
            items += MenuItem(Strings["file.serial"]).apply {
                accelerator = accelerator("file.serial")
                setOnAction { openSerialSession() }
            }
            items += MenuItem(Strings["file.ssh"]).apply {
                accelerator = accelerator("file.ssh")
                setOnAction { openSshSession() }
            }
            items += SeparatorMenuItem()
            items += MenuItem(Strings["file.exit"]).apply {
                accelerator = accelerator("file.exit")
                setOnAction { stage.fireEvent(javafx.stage.WindowEvent(stage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST)) }
            }
        }
        val edit = Menu(Strings["menu.edit"]).apply {
            items += MenuItem(Strings["edit.copy"]).apply {
                accelerator = accelerator("edit.copy")
                setOnAction { currentTerminal()?.copySelection() }
            }
            items += MenuItem(Strings["edit.paste"]).apply {
                accelerator = accelerator("edit.paste")
                setOnAction { currentTerminal()?.pasteFromClipboard() }
            }
            items += SeparatorMenuItem()
            items += MenuItem(Strings["edit.clear"]).apply {
                setOnAction { currentTerminal()?.clear() }
            }
        }
        val setup = Menu(Strings["menu.setup"]).apply {
            items += MenuItem(Strings["setup.serial"]).apply { setOnAction { openSerialSession() } }
            items += MenuItem(Strings["setup.ssh"]).apply { setOnAction { openSshSession() } }
            items += MenuItem(Strings["setup.telnet"]).apply { setOnAction { openTelnetSession() } }
            items += MenuItem(Strings["setup.tcpraw"]).apply { setOnAction { openTcpRawSession() } }
            items += SeparatorMenuItem()
            items += CheckMenuItem(Strings["setup.darkTheme"]).apply {
                isSelected = theme.isDark
                setOnAction {
                    theme.toggle()
                    rootPane.scene?.let { theme.applyTo(it) }
                    isSelected = theme.isDark
                    applyThemeToTerminals()
                    persist { it.copy(theme = theme.mode.name) }
                    refreshLabels()
                }
            }
            items += MenuItem(Strings["setup.font"]).apply {
                setOnAction { openFontDialog() }
            }
            items += buildLanguageMenu()
            items += SeparatorMenuItem()
            items += MenuItem(Strings["setup.macros"]).apply {
                accelerator = accelerator("setup.macros")
                setOnAction { macroWindow.show() }
            }
            items += SeparatorMenuItem()
            items += MenuItem(Strings["setup.startLog"]).apply { setOnAction { startSessionLog() } }
            items += MenuItem(Strings["setup.stopLog"]).apply { setOnAction { stopSessionLog() } }
            items += SeparatorMenuItem()
            items += MenuItem(Strings["setup.capturePng"]).apply { setOnAction { capturePng() } }
            items += MenuItem(Strings["setup.exportBuffer"]).apply { setOnAction { exportBufferText() } }
        }
        val control = Menu(Strings["menu.control"]).apply {
            items += MenuItem(Strings["control.connect"]).apply { setOnAction { currentController()?.connect() } }
            items += MenuItem(Strings["control.disconnect"]).apply { setOnAction { currentController()?.disconnect() } }
            items += MenuItem(Strings["control.break"]).apply { setOnAction { sendBreakOnCurrent() } }
            items += SeparatorMenuItem()
            items += MenuItem(Strings["control.sendXmodem"]).apply { setOnAction { sendFileXmodem() } }
            items += MenuItem(Strings["control.recvXmodem"]).apply { setOnAction { receiveFileXmodem() } }
            items += MenuItem(Strings["control.sendZmodem"]).apply { setOnAction { sendFileZmodem() } }
            items += MenuItem(Strings["control.recvZmodem"]).apply { setOnAction { receiveFileZmodem() } }
            items += MenuItem(Strings["control.sendYmodem"]).apply { setOnAction { sendFilesYmodem() } }
            items += MenuItem(Strings["control.recvYmodem"]).apply { setOnAction { receiveFilesYmodem() } }
        }
        val windowMenu = Menu(Strings["menu.window"]).apply {
            items += MenuItem(Strings["window.closeTab"]).apply {
                accelerator = accelerator("window.closeTab")
                setOnAction { tabPane.selectionModel.selectedItem?.let { closeTab(it) } }
            }
        }
        val help = Menu(Strings["menu.help"]).apply {
            items += MenuItem(Strings["help.about"]).apply {
                setOnAction {
                    Alert(Alert.AlertType.INFORMATION).apply {
                        title = Strings["about.title"]
                        headerText = Strings["about.header"]
                        contentText = Strings["about.body"]
                    }.showAndWait()
                }
            }
        }
        return MenuBar(file, edit, setup, control, windowMenu, help)
    }

    private fun buildLanguageMenu(): Menu = Menu(Strings["setup.language"]).apply {
        val group = ToggleGroup()
        items += RadioMenuItem(Strings["setup.languageEs"]).apply {
            toggleGroup = group
            isSelected = settings.locale == "es"
            setOnAction { changeLocale("es") }
        }
        items += RadioMenuItem(Strings["setup.languageEn"]).apply {
            toggleGroup = group
            isSelected = settings.locale == "en"
            setOnAction { changeLocale("en") }
        }
    }

    private fun changeLocale(code: String) {
        Strings.setLocale(code)
        persist { it.copy(locale = code) }
        rebuildMenusAndLabels()
    }

    private fun rebuildMenusAndLabels() {
        rootPane.top = buildMenuBar()
        refreshLabels()
    }

    private fun openFontDialog() {
        val dialog = FontConfigDialog(settings.terminalFontFamily, settings.terminalFontSize)
        val choice = dialog.showAndWait().orElse(null) ?: return
        persist { it.copy(terminalFontFamily = choice.family, terminalFontSize = choice.size) }
        applyFontToTerminals()
    }

    private fun applyThemeToTerminals() {
        val c = theme.terminalColors
        controllers.values.forEach { it.terminal.applyColors(c.foreground, c.background, c.cursor, c.selection) }
        // Welcome and any extra terminals not tracked in controllers
        forEachTerminal { it.applyColors(c.foreground, c.background, c.cursor, c.selection) }
    }

    private fun applyFontToTerminals() {
        controllers.values.forEach { it.terminal.applyFont(settings.terminalFontFamily, settings.terminalFontSize) }
        forEachTerminal { it.applyFont(settings.terminalFontFamily, settings.terminalFontSize) }
    }

    private fun forEachTerminal(action: (TerminalView) -> Unit) {
        for (tab in tabPane.tabs) {
            (tab.content as? TerminalView)?.let(action)
        }
    }

    private fun buildStatusBar(): Region {
        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        return HBox(12.0, statusLabel, spacer, protocolLabel, Separator(), themeLabel).apply {
            styleClass += "status-bar"
        }
    }

    private fun openWelcomeTab() {
        val terminal = newTerminal()
        terminal.append(Strings["welcome.line1"] + "\n")
        terminal.append(Strings["welcome.line2"] + "\n")
        terminal.onInput = { input -> terminal.append(input) }
        val tab = Tab(Strings["welcome.tabTitle"], terminal).apply { isClosable = true }
        tabPane.tabs += tab
        tabPane.selectionModel.select(tab)
    }

    private fun newTerminal(): TerminalView {
        val terminal = TerminalView(settings.terminalFontFamily, settings.terminalFontSize)
        val c = theme.terminalColors
        terminal.applyColors(c.foreground, c.background, c.cursor, c.selection)
        return terminal
    }

    private fun openSerialSession() {
        val cfg = SerialConfigDialog().showAndWait().orElse(null) ?: return
        openSession(cfg, cfg.portName, SerialConnection(cfg))
    }

    private fun openSshSession() {
        val cfg = SshConfigDialog().showAndWait().orElse(null) ?: return
        openSession(cfg, "${cfg.username}@${cfg.host}", SshConnection(cfg))
    }

    private fun openTelnetSession() {
        val cfg = TelnetConfigDialog().showAndWait().orElse(null) ?: return
        val protocol = if (cfg.useTls) "telnets" else "telnet"
        openSession(cfg, "$protocol://${cfg.host}:${cfg.port}", TelnetConnection(cfg))
    }

    private fun openTcpRawSession() {
        val cfg = TcpRawConfigDialog().showAndWait().orElse(null) ?: return
        openSession(cfg, "${cfg.host}:${cfg.port}", RawTcpConnection(cfg))
    }

    private fun openSession(config: ConnectionConfig, name: String, connection: Connection) {
        val session = Session(SessionId.random(), name, config, connection)
        val terminal = newTerminal()
        terminal.append("→ ${config.displayName}\n")

        val controller = TerminalSessionController(session, terminal)
        val tab = Tab().apply {
            content = terminal
            isClosable = true
        }
        bindTabTitle(tab, controller)
        tab.setOnClosed { stopController(tab) }

        controllers[tab] = controller
        tabPane.tabs += tab
        tabPane.selectionModel.select(tab)
        viewModel.addSession(session)
        controller.start()
    }

    private fun bindTabTitle(tab: Tab, controller: TerminalSessionController) {
        tab.textProperty().bind(Bindings.createStringBinding({
            val state = controller.state.value
            val base = controller.session.name
            when (state) {
                ConnectionState.CONNECTED -> "● $base"
                ConnectionState.CONNECTING -> "$base …"
                ConnectionState.DISCONNECTED -> "○ $base"
                ConnectionState.DISCONNECTING -> "$base …"
                ConnectionState.ERROR -> "✗ $base"
                null -> base
            }
        }, controller.state))
    }

    private fun bindStatusToTab(tab: Tab?) {
        protocolLabel.textProperty().unbind()
        val ctl = tab?.let { controllers[it] }
        if (ctl == null) {
            protocolLabel.text = Strings["status.noConnection"]
        } else {
            protocolLabel.textProperty().bind(Bindings.createStringBinding({
                "${ctl.session.config.displayName} — ${ctl.state.value}"
            }, ctl.state))
        }
    }

    private fun closeTab(tab: Tab) {
        stopController(tab)
        tabPane.tabs.remove(tab)
    }

    private fun stopController(tab: Tab) {
        controllers.remove(tab)?.let {
            LogManager.stop(it.session.id.value)
            it.stop()
            viewModel.removeSession(it.session)
        }
    }

    private fun stopAllControllers() {
        LogManager.stopAll()
        controllers.values.toList().forEach {
            it.stop()
            viewModel.removeSession(it.session)
        }
        controllers.clear()
    }

    private fun startSessionLog() {
        val ctl = currentController() ?: run {
            statusLabel.text = Strings["status.noSession"]
            return
        }
        val cfg = LogConfigDialog(ctl.session.name).showAndWait().orElse(null) ?: return
        runCatching { LogManager.start(ctl.session.id.value, cfg) }
            .onSuccess { statusLabel.text = Strings.format("status.logActive", cfg.basePath) }
            .onFailure {
                log.warn("No se pudo iniciar log", it)
                statusLabel.text = Strings.format("status.captureError", it.message ?: "")
            }
    }

    private fun stopSessionLog() {
        val ctl = currentController() ?: return
        LogManager.stop(ctl.session.id.value)
        statusLabel.text = Strings["status.logStopped"]
    }

    private fun capturePng() {
        val terminal = currentTerminal() ?: return
        val file = FileChooser().apply {
            title = Strings["setup.capturePng"]
            initialFileName = "terminal.png"
            extensionFilters.add(FileChooser.ExtensionFilter("PNG", "*.png"))
        }.showSaveDialog(stage) ?: return
        runCatching { TerminalCapture.captureToPng(terminal, file) }
            .onSuccess { statusLabel.text = Strings.format("status.captureSaved", file.name) }
            .onFailure { statusLabel.text = Strings.format("status.captureError", it.message ?: "") }
    }

    private fun exportBufferText() {
        val terminal = currentTerminal() ?: return
        val file = FileChooser().apply {
            title = Strings["setup.exportBuffer"]
            initialFileName = "terminal.txt"
            extensionFilters.add(FileChooser.ExtensionFilter("TXT", "*.txt"))
        }.showSaveDialog(stage) ?: return
        runCatching { TerminalCapture.exportText(terminal, file) }
            .onSuccess { statusLabel.text = Strings.format("status.bufferExported", file.name) }
            .onFailure { statusLabel.text = Strings.format("status.exportError", it.message ?: "") }
    }

    private fun sendFileXmodem() {
        val ctl = currentController() ?: run {
            statusLabel.text = Strings["status.noSession"]
            return
        }
        val file = FileChooser().apply { title = Strings["control.sendXmodem"] }.showOpenDialog(stage) ?: return
        val tc = TransferController(ctl.session.connection, TransferDirection.SEND, file, file.length())
        TransferProgressDialog(stage, tc, "${Strings["control.sendXmodem"]} ${file.name}").show()
        tc.start()
    }

    private fun receiveFileXmodem() {
        val ctl = currentController() ?: run {
            statusLabel.text = Strings["status.noSession"]
            return
        }
        val file = FileChooser().apply {
            title = Strings["control.recvXmodem"]
            initialFileName = "received.bin"
        }.showSaveDialog(stage) ?: return
        val tc = TransferController(ctl.session.connection, TransferDirection.RECEIVE, file, -1, TransferProtocol.XMODEM)
        TransferProgressDialog(stage, tc, "${Strings["control.recvXmodem"]} ${file.name}").show()
        tc.start()
    }

    private fun sendFileZmodem() {
        val ctl = currentController() ?: run {
            statusLabel.text = Strings["status.noSession"]
            return
        }
        val file = FileChooser().apply { title = Strings["control.sendZmodem"] }.showOpenDialog(stage) ?: return
        val tc = TransferController(ctl.session.connection, TransferDirection.SEND, file, file.length(), TransferProtocol.ZMODEM)
        TransferProgressDialog(stage, tc, "${Strings["control.sendZmodem"]} ${file.name}").show()
        tc.start()
    }

    private fun receiveFileZmodem() {
        val ctl = currentController() ?: run {
            statusLabel.text = Strings["status.noSession"]
            return
        }
        val dir = DirectoryChooser().apply {
            title = Strings["control.recvZmodem"]
            initialDirectory = java.io.File(System.getProperty("user.home"))
        }.showDialog(stage) ?: return
        val tc = TransferController(ctl.session.connection, TransferDirection.RECEIVE, dir, -1, TransferProtocol.ZMODEM)
        TransferProgressDialog(stage, tc, "${Strings["control.recvZmodem"]} → ${dir.name}").show()
        tc.start()
    }

    private fun sendFilesYmodem() {
        val ctl = currentController() ?: run {
            statusLabel.text = Strings["status.noSession"]
            return
        }
        val files = FileChooser().apply { title = Strings["control.sendYmodem"] }
            .showOpenMultipleDialog(stage) ?: return
        if (files.isEmpty()) return
        val totalSize = files.sumOf { it.length() }
        val tc = TransferController(
            connection = ctl.session.connection,
            direction = TransferDirection.SEND,
            target = files.first(),
            fileSize = totalSize,
            protocol = TransferProtocol.YMODEM,
            batchFiles = files,
        )
        TransferProgressDialog(stage, tc, "${Strings["control.sendYmodem"]} (${files.size})").show()
        tc.start()
    }

    private fun receiveFilesYmodem() {
        val ctl = currentController() ?: run {
            statusLabel.text = Strings["status.noSession"]
            return
        }
        val dir = DirectoryChooser().apply {
            title = Strings["control.recvYmodem"]
            initialDirectory = java.io.File(System.getProperty("user.home"))
        }.showDialog(stage) ?: return
        val tc = TransferController(ctl.session.connection, TransferDirection.RECEIVE, dir, -1, TransferProtocol.YMODEM)
        TransferProgressDialog(stage, tc, "${Strings["control.recvYmodem"]} → ${dir.name}").show()
        tc.start()
    }

    private fun sendBreakOnCurrent() {
        val ctl = currentController() ?: return
        val conn = ctl.session.connection
        if (conn is SerialConnection) {
            ioScope.launch {
                runCatching { conn.sendBreak(250) }
                    .onFailure { log.warn("BREAK falló", it) }
            }
        }
    }

    private fun currentController(): TerminalSessionController? = controllers[tabPane.selectionModel.selectedItem]
    private fun currentTerminal(): TerminalView? = tabPane.selectionModel.selectedItem?.content as? TerminalView

    private fun refreshLabels() {
        statusLabel.text = Strings["status.ready"]
        bindStatusToTab(tabPane.selectionModel.selectedItem)
        themeLabel.text = if (theme.isDark) Strings["status.themeDark"] else Strings["status.themeLight"]
    }

    private fun accelerator(key: String): KeyCombination? = settings.accelerators[key]?.let {
        runCatching { KeyCombination.keyCombination(it) }.getOrNull()
    }

    private fun persist(transform: (AppSettings) -> AppSettings) {
        settings = transform(settings)
        SettingsStore.save(settings)
    }

    private fun parseTheme(name: String): ThemeMode = runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.DARK)
}