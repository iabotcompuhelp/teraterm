package com.opentermx.app.ui

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.AppSettings
import com.opentermx.app.settings.SettingsStore
import com.opentermx.app.rest.RestApiHooksImpl
import com.opentermx.app.rest.RestApiManager
import com.opentermx.app.ui.ai.AiChatPanel
import com.opentermx.app.ui.dialog.AdditionalSettingsDialog
import com.opentermx.app.ui.dialog.AiAssistantDialog
import com.opentermx.app.ui.dialog.RestApiDialog
import com.opentermx.app.ui.dialog.ErrorDialog
import com.opentermx.app.ui.dialog.FontConfigDialog
import com.opentermx.app.ui.dialog.GeneralSettingsDialog
import com.opentermx.app.ui.dialog.JavaFxHostKeyVerifier
import com.opentermx.app.ui.dialog.KeyboardDialog
import com.opentermx.app.ui.dialog.KeybindingsDialog
import com.opentermx.app.ui.dialog.LogConfigDialog
import com.opentermx.app.ui.dialog.NewConnectionChoice
import com.opentermx.app.ui.dialog.NewConnectionDialog
import com.opentermx.app.ui.dialog.PortForwardDialog
import com.opentermx.app.ui.dialog.ProxyConfigDialog
import com.opentermx.app.ui.dialog.QuickGuideDialog
import com.opentermx.app.ui.dialog.ShortcutsViewerDialog
import com.opentermx.app.ui.dialog.SshAuthDialog
import com.opentermx.app.ui.dialog.SshGeneralDialog
import com.opentermx.app.ui.dialog.SshKeyGeneratorDialog
import com.opentermx.app.ui.dialog.SshVersion
import com.opentermx.app.ui.dialog.ScrollbackDialog
import com.opentermx.app.ui.dialog.SerialConfigDialog
import com.opentermx.app.ui.dialog.SerialSignalsDialog
import com.opentermx.app.ui.dialog.SshConfigDialog
import com.opentermx.app.ui.dialog.SystemInfoDialog
import com.opentermx.app.ui.dialog.TcpIpConfigDialog
import com.opentermx.app.ui.dialog.TerminalConfigDialog
import com.opentermx.app.ui.dialog.TftpClientDialog
import com.opentermx.app.ui.dialog.TftpServerDialog
import com.opentermx.app.ui.dialog.WindowConfigDialog
import com.opentermx.app.ui.macro.MacroUiBridgeImpl
import com.opentermx.app.ui.macro.MacroWindow
import com.opentermx.app.ui.sftp.SftpPanel
import com.opentermx.app.ui.terminal.TerminalCapture
import com.opentermx.app.ui.terminal.TerminalEngine
import com.opentermx.app.ui.terminal.TerminalView
import com.opentermx.app.ui.tftp.TftpServerManager
import com.opentermx.app.ui.tftp.TftpTransferManager
import com.opentermx.app.ui.tftp.TftpTransfersPanel
import com.opentermx.app.ui.transfer.TransferProgressDialog
import com.opentermx.app.viewmodel.AppViewModel
import com.opentermx.app.viewmodel.TerminalSessionController
import com.opentermx.app.viewmodel.TransferController
import com.opentermx.app.viewmodel.TransferProtocol
import com.opentermx.common.connection.Connection
import com.opentermx.common.connection.ConnectionConfig
import com.opentermx.common.connection.ConnectionState
import com.opentermx.common.connection.ProxyConfig
import com.opentermx.common.connection.SerialConfig
import com.opentermx.common.connection.SshAuth
import com.opentermx.common.connection.SshConfig
import com.opentermx.common.connection.TcpRawConfig
import com.opentermx.common.connection.TelnetConfig
import com.opentermx.common.session.Session
import com.opentermx.common.session.SessionId
import com.opentermx.logger.LogManager
import com.opentermx.serial.SerialConnectionFactory
import com.opentermx.serial.SerialPortConnection
import com.opentermx.ssh.SshConnection
 import com.opentermx.telnet.RawTcpConnection
import com.opentermx.telnet.TelnetConnection
import com.opentermx.transfer.TransferDirection
import javafx.beans.binding.Bindings
import javafx.geometry.Insets
import javafx.geometry.Pos
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
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.StackPane
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

    private val tabPane = TabPane().apply {
        tabClosingPolicy = TabPane.TabClosingPolicy.ALL_TABS
        // Sin esto, el TabPane reclama focus en cada mouse press dentro de su área
        // de contenido y se lo roba al Canvas del terminal — los KeyEvent nunca
        // llegan al filter de TerminalView. (Reproducido con [focus-diag]).
        isFocusTraversable = false
    }
    private val statusLabel = Label()
    private val protocolLabel = Label()
    private val themeLabel = Label()
    private val tftpServerLabel = Label().apply {
        cursor = javafx.scene.Cursor.HAND
        styleClass += "status-tftp"
        setOnMouseClicked { openTftpServerDialog() }
        isVisible = false
        isManaged = false
    }
    private val tftpClientLabel = Label().apply {
        cursor = javafx.scene.Cursor.HAND
        styleClass += "status-tftp"
        setOnMouseClicked { openTftpTransfersPanel() }
        isVisible = false
        isManaged = false
    }
    private val aiStatusLabel = Label().apply {
        cursor = javafx.scene.Cursor.HAND
        styleClass += "status-ai"
        setOnMouseClicked { openAiAssistantConfig() }
    }
    private val restApiLabel = Label().apply {
        cursor = javafx.scene.Cursor.HAND
        styleClass += "status-rest"
        setOnMouseClicked { openRestApiConfig() }
        isVisible = false; isManaged = false
    }
    private val mcpServerLabel = Label().apply {
        cursor = javafx.scene.Cursor.HAND
        styleClass += "status-mcp"
        setOnMouseClicked { openAiAssistantConfig() }
        isVisible = false; isManaged = false
    }
    private var tftpServerDialogRef: TftpServerDialog? = null
    private var tftpTransfersPanelRef: TftpTransfersPanel? = null

    private val controllers: MutableMap<Tab, TerminalSessionController> = mutableMapOf()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val macroAiBridge: com.opentermx.macro.MacroAiBridge by lazy {
        com.opentermx.app.ui.ai.JavaFxMacroAiBridge(
            owner = { stage },
            settingsProvider = { settings.aiAssistant },
        )
    }
    private val macroWindow by lazy {
        MacroWindow(stage, { controllers.values.toList() }, { macroAiBridge })
    }
    private val hostKeyVerifier = JavaFxHostKeyVerifier(stage)

    private lateinit var rootPane: BorderPane
    private lateinit var savedConnectionsListView: SavedConnectionsListView

    fun show() {
        savedConnectionsListView = SavedConnectionsListView(
            savedProvider = { settings.savedConnections },
            onQuickConnect = { quickConnectSaved(it) },
            onEdit = { editSavedConnection(it) },
            onDelete = { deleteSavedConnection(it) },
        )
        val leftPane = javafx.scene.control.SplitPane(
            SessionListView(viewModel),
            savedConnectionsListView,
        ).apply {
            orientation = javafx.geometry.Orientation.VERTICAL
            setDividerPositions(0.5)
            prefWidth = 240.0
        }
        rootPane = BorderPane().apply {
            top = buildTopBar()
            center = buildCenterWithWatermark()
            bottom = buildStatusBar()
            left = leftPane
        }
        // Capeamos el tamaño inicial a los visualBounds de la pantalla primaria para
        // que la barra de título y la status bar nunca queden fuera de la pantalla en
        // laptops 1366x768 o monitores con escalado DPI >100%. Reservamos ~40px para
        // la taskbar/decoraciones del SO antes de calcular el preferido.
        val visualBounds = javafx.stage.Screen.getPrimary().visualBounds
        val sceneW = minOf(1100.0, visualBounds.width - 40.0).coerceAtLeast(720.0)
        val sceneH = minOf(720.0, visualBounds.height - 60.0).coerceAtLeast(480.0)
        val scene = Scene(rootPane, sceneW, sceneH)
        theme.applyTo(scene)
        refreshLabels()

        tabPane.selectionModel.selectedItemProperty().addListener { _, _, newTab ->
            bindStatusToTab(newTab)
        }

        // hideTitleBar can only be applied before stage.show(); once set, toggling at runtime
        // requires re-creating the stage (we surface that as a status note in openWindowConfig).
        if (settings.window.hideTitleBar) {
            runCatching { stage.initStyle(javafx.stage.StageStyle.UNDECORATED) }
                .onFailure { log.info("No se pudo aplicar hideTitleBar: {}", it.message) }
        }
        stage.title = settings.window.titlePrefix.ifBlank { "COMPUHELP" }
        stage.opacity = settings.window.transparency
        stage.scene = scene
        stage.minWidth = 720.0
        stage.minHeight = 480.0
        stage.setOnCloseRequest {
            stopAllControllers()
            TftpServerManager.stop()
            TftpTransferManager.cancelAll()
            RestApiManager.stop()
            com.opentermx.app.ui.mcp.McpServerManager.stop()
            com.opentermx.app.ui.ai.KnowledgeBaseHolder.shutdown()
        }
        TftpServerManager.runningProperty.addListener { _, _, _ -> updateTftpServerLabel() }
        TftpServerManager.portProperty.addListener { _, _, _ -> updateTftpServerLabel() }
        TftpTransferManager.runningCountProperty.addListener { _, _, _ -> updateTftpClientLabel() }
        TftpTransferManager.transfers.addListener(javafx.beans.InvalidationListener { updateTftpClientLabel() })
        updateTftpServerLabel()
        updateTftpClientLabel()
        updateAiStatusLabel()
        if (!settings.additional.terminalOnlyMode) {
            bootRestApiIfEnabled()
            bootMcpServerIfEnabled()
        }
        applyTerminalOnlyMode()
        subscribeConnectionErrorPopups()
        // Phase 2.5 T3: siembra el flag de verbose Telnet desde settings persistidos
        // antes de que el usuario pueda abrir una sesión. Mantenemos el cambio acá (no
        // antes) porque la system property es un side effect de UI lifecycle, no algo
        // que tenga sentido al construir el MainWindow para tests.
        System.setProperty("opentermx.telnet.verboseLog", settings.additional.telnetVerboseLog.toString())
        stage.centerOnScreen()
        stage.show()

        openWelcomeTab()
    }

    private fun buildMenuBar(): MenuBar {
        val file = Menu(Strings["menu.file"]).apply {
            items += MenuItem(Strings["file.newConnection"]).apply {
                accelerator = accelerator("file.newSession")
                setOnAction { openNewConnectionDialog() }
            }
            items += MenuItem(Strings["file.newSession"]).apply {
                setOnAction { openWelcomeTab() }
            }
            items += MenuItem(Strings["file.newWeb"]).apply {
                setOnAction { openNewWebSessionDialog() }
            }
            items += MenuItem(Strings["file.newRdp"]).apply {
                setOnAction { openNewRdpSessionDialog() }
            }
            items += SeparatorMenuItem()
            items += MenuItem(Strings["file.sftp"]).apply {
                setOnAction { openSftpForCurrentSession() }
            }
            items += MenuItem(Strings["file.portForward"]).apply {
                setOnAction { openPortForwardDialog() }
            }
            items += buildTransferMenu()
            items += SeparatorMenuItem()
            items += MenuItem(Strings["setup.capturePng"]).apply { setOnAction { capturePng() } }
            items += MenuItem(Strings["setup.exportBuffer"]).apply { setOnAction { exportBufferText() } }
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
            // Grupo 1: terminal y apariencia.
            items += MenuItem(Strings["setup.terminal"]).apply { setOnAction { openTerminalConfig() } }
            items += MenuItem(Strings["setup.window"]).apply { setOnAction { openWindowConfig() } }
            items += buildFontMenu()
            items += MenuItem(Strings["setup.keyboard"]).apply { setOnAction { openKeyboardConfig() } }
            items += MenuItem(Strings["setup.shortcuts"]).apply { setOnAction { openKeybindingsDialog() } }
            items += SeparatorMenuItem()
            // Grupo 2: conexiones.
            items += MenuItem(Strings["setup.serialPort"]).apply { setOnAction { openSerialPortSetup() } }
            items += MenuItem(Strings["setup.proxy"]).apply { setOnAction { openProxyConfig() } }
            items += MenuItem(Strings["setup.sshGen"]).apply { setOnAction { openSshGeneralConfig() } }
            items += MenuItem(Strings["setup.sshAuth"]).apply { setOnAction { openSshAuthConfig() } }
            items += MenuItem(Strings["setup.sshForwarding"]).apply { setOnAction { openPortForwardDialog() } }
            items += MenuItem(Strings["setup.sshKeygen"]).apply { setOnAction { openSshKeyGenerator() } }
            items += MenuItem(Strings["setup.savedConnections"]).apply { setOnAction { openSavedConnectionsDialog() } }
            items += SeparatorMenuItem()
            // Grupo 3: red.
            items += MenuItem(Strings["setup.tcpip"]).apply { setOnAction { openTcpIpConfig() } }
            items += SeparatorMenuItem()
            // Grupo 4: general.
            items += MenuItem(Strings["setup.general"]).apply { setOnAction { openGeneralSettings() } }
            items += MenuItem(Strings["setup.additional"]).apply { setOnAction { openAdditionalSettings() } }
            items += MenuItem(Strings["setup.highlight"]).apply { setOnAction { openHighlightConfigDialog() } }
            items += SeparatorMenuItem()
            // Grupo 5: IA + privacidad. Cuando está activo `terminalOnlyMode`,
            // los accesos a IA/MCP/REST se omiten del menú y sólo queda el toggle
            // del modo terminal (con PIN) para volver a habilitarlos.
            if (!settings.additional.terminalOnlyMode) {
                items += MenuItem(Strings["setup.aiAssistant"]).apply {
                    accelerator = accelerator("setup.aiAssistant")
                    setOnAction { openAiAssistantConfig() }
                }
                items += MenuItem(Strings["setup.restApi"]).apply {
                    accelerator = accelerator("setup.restApi")
                    setOnAction { openRestApiConfig() }
                }
            }
            items += MenuItem(Strings["setup.terminalOnly"]).apply {
                setOnAction { openTerminalOnlyModeConfig() }
            }
            items += SeparatorMenuItem()
            // Grupo 6: persistencia.
            items += MenuItem(Strings["setup.saveSetup"]).apply { setOnAction { saveSetup() } }
            items += MenuItem(Strings["setup.restoreSetup"]).apply { setOnAction { restoreSetup() } }
            items += MenuItem(Strings["setup.setupDir"]).apply { setOnAction { showSetupDirectory() } }
            items += SeparatorMenuItem()
            items += MenuItem(Strings["setup.loadKeymap"]).apply { setOnAction { loadKeyMap() } }
        }
        val control = Menu(Strings["menu.control"]).apply {
            items += MenuItem(Strings["control.connect"]).apply { setOnAction { currentController()?.connect() } }
            items += MenuItem(Strings["control.disconnect"]).apply { setOnAction { currentController()?.disconnect() } }
            items += MenuItem(Strings["control.break"]).apply { setOnAction { sendBreakOnCurrent() } }
            items += MenuItem(Strings["control.serialSignals"]).apply { setOnAction { openSerialSignals() } }
            items += SeparatorMenuItem()
            items += MenuItem(Strings["setup.macros"]).apply {
                accelerator = accelerator("setup.macros")
                setOnAction { macroWindow.show() }
            }
            items += SeparatorMenuItem()
            items += MenuItem(Strings["setup.startLog"]).apply { setOnAction { startSessionLog() } }
            items += MenuItem(Strings["setup.stopLog"]).apply { setOnAction { stopSessionLog() } }
            items += SeparatorMenuItem()
            items += MenuItem(Strings["control.sendXmodem"]).apply { setOnAction { sendFileXmodem() } }
            items += MenuItem(Strings["control.recvXmodem"]).apply { setOnAction { receiveFileXmodem() } }
        }
        val windowMenu = Menu(Strings["menu.window"]).apply {
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
            items += buildLanguageMenu()
            items += SeparatorMenuItem()
            if (!settings.additional.terminalOnlyMode) {
                items += MenuItem(Strings["window.toggleAiChat"]).apply {
                    accelerator = accelerator("window.toggleAiChat")
                    setOnAction { toggleAiChatPanel() }
                }
                items += SeparatorMenuItem()
            }
            items += MenuItem(Strings["window.closeTab"]).apply {
                accelerator = accelerator("window.closeTab")
                setOnAction { tabPane.selectionModel.selectedItem?.let { closeTab(it) } }
            }
        }
        val help = buildHelpMenu()
        return MenuBar(file, edit, setup, control, windowMenu, help)
    }

    private fun buildHelpMenu(): Menu = Menu(Strings["menu.help"]).apply {
        items += MenuItem(Strings["help.quickGuide"]).apply {
            setOnAction { QuickGuideDialog().also { it.initOwner(stage) }.showAndWait() }
        }
        items += MenuItem(Strings["help.shortcuts"]).apply {
            setOnAction {
                ShortcutsViewerDialog(settings.accelerators)
                    .also { it.initOwner(stage) }
                    .showAndWait()
            }
        }
        items += MenuItem(Strings["help.systemInfo"]).apply {
            setOnAction { SystemInfoDialog().also { it.initOwner(stage) }.showAndWait() }
        }
        items += MenuItem(Strings["help.openLogsDir"]).apply {
            setOnAction { openLogsDirectory() }
        }
        items += SeparatorMenuItem()
        items += MenuItem(Strings["help.openSpec"]).apply {
            setOnAction { openSpecDocumentation() }
        }
        items += MenuItem(Strings["help.reportIssue"]).apply {
            setOnAction { reportIssueViaErrorDialog() }
        }
        items += SeparatorMenuItem()
        items += MenuItem(Strings["help.about"]).apply {
            setOnAction { showAboutDialog() }
        }
    }

    private fun showAboutDialog() {
        ErrorDialog.info(
            owner = stage,
            title = Strings["about.title"],
            header = Strings["about.header"],
            message = Strings["about.body"],
            details = Strings["about.details"],
        )
    }

    private fun openLogsDirectory() {
        val dir = SettingsStore.configDir.toFile()
        if (!dir.isDirectory) dir.mkdirs()
        runCatching { java.awt.Desktop.getDesktop().open(dir) }
            .onFailure {
                log.info("Desktop.open no disponible", it)
                ErrorDialog.warning(
                    owner = stage,
                    title = Strings["help.openLogsDir"],
                    header = Strings["error.openLogsDir.header"],
                    message = Strings.format("error.openLogsDir.body", dir.absolutePath),
                    cause = it,
                )
            }
        statusLabel.text = Strings.format("status.setupDirOpen", dir.absolutePath)
    }

    private fun openSpecDocumentation() {
        // The master spec ships next to the IDE project (.idea/Teraterm v3.md). If it isn't there
        // we tell the user explicitly with the same dialog used elsewhere — no silent failure.
        val candidates = listOf(
            java.io.File(System.getProperty("user.dir"), ".idea/Teraterm v3.md"),
            java.io.File(System.getProperty("user.dir"), ".idea/teraterm.md"),
        )
        val file = candidates.firstOrNull { it.isFile }
        if (file == null) {
            ErrorDialog.info(
                owner = stage,
                title = Strings["help.openSpec"],
                header = Strings["help.openSpec.missing.header"],
                message = Strings.format("help.openSpec.missing.body", candidates.joinToString("\n") { it.absolutePath }),
            )
            return
        }
        runCatching { java.awt.Desktop.getDesktop().open(file) }
            .onFailure {
                ErrorDialog.warning(
                    owner = stage,
                    title = Strings["help.openSpec"],
                    header = Strings["error.openSpec.header"],
                    message = Strings.format("error.openSpec.body", file.absolutePath),
                    cause = it,
                )
            }
    }

    private fun reportIssueViaErrorDialog() {
        // "Report issue" doesn't require the user to actually have an error in front of them —
        // we show the same expandable diagnostic block so they can copy it into a ticket.
        ErrorDialog.info(
            owner = stage,
            title = Strings["help.reportIssue"],
            header = Strings["help.reportIssue.header"],
            message = Strings["help.reportIssue.body"],
            details = buildEnvironmentSnapshot(),
        )
    }

    private fun buildEnvironmentSnapshot(): String {
        val p = System.getProperties()
        return buildString {
            appendLine("OS    : ${p.getProperty("os.name")} ${p.getProperty("os.version")} (${p.getProperty("os.arch")})")
            appendLine("Java  : ${p.getProperty("java.version")}")
            appendLine("Locale: ${java.util.Locale.getDefault()}")
            appendLine("Theme : ${settings.theme}")
            appendLine("Lang  : ${settings.locale}")
            appendLine("Tabs  : ${tabPane.tabs.size}")
            appendLine("Active sessions: ${controllers.size}")
        }
    }

    private fun buildFontMenu(): Menu = Menu(Strings["setup.fontMenu"]).apply {
        items += MenuItem(Strings["setup.font"]).apply { setOnAction { openFontDialog() } }
        items += MenuItem(Strings["setup.scrollback"]).apply { setOnAction { openScrollbackDialog() } }
    }

    private fun buildTransferMenu(): Menu = Menu(Strings["file.transfer"]).apply {
        items += MenuItem(Strings["file.transfer.tftpClient"]).apply {
            setOnAction { openTftpClientDialog() }
        }
        items += MenuItem(Strings["file.transfer.tftpServer"]).apply {
            setOnAction { openTftpServerDialog() }
        }
    }

    private fun openTftpClientDialog() {
        val initialHost = controllers.values
            .firstOrNull { it.session.connection !is SerialPortConnection }
            ?.session?.config?.let { cfg ->
                when (cfg) {
                    is TelnetConfig -> cfg.host
                    is TcpRawConfig -> cfg.host
                    is SshConfig -> cfg.host
                    else -> ""
                }
            }
            ?: settings.recentHosts.firstOrNull().orEmpty()
        TftpClientDialog(
            owner = stage,
            initialHost = initialHost,
            defaultPort = settings.additional.tftpDefaultPort,
            defaultBlockSize = settings.additional.tftpDefaultBlocksize,
            csvLogPath = settings.additional.tftpCsvLogPath,
        ).show()
    }

    private fun openTftpServerDialog() {
        // Reuse a single dialog instance — focus it instead of stacking new windows.
        val existing = tftpServerDialogRef
        if (existing != null && existing.isShowing) {
            existing.toFront()
            existing.requestFocus()
            return
        }
        val dialog = TftpServerDialog(
            owner = stage,
            defaultPort = settings.additional.tftpDefaultPort,
            defaultRoot = settings.additional.tftpDefaultRoot,
            csvLogPath = settings.additional.tftpCsvLogPath,
        )
        tftpServerDialogRef = dialog
        dialog.show()
    }

    private fun updateTftpServerLabel() {
        val running = TftpServerManager.isRunning
        tftpServerLabel.isVisible = running
        tftpServerLabel.isManaged = running
        if (running) {
            tftpServerLabel.text = Strings.format("status.tftpServerBadge", TftpServerManager.actualPort)
            tftpServerLabel.tooltip = javafx.scene.control.Tooltip(Strings["status.tftpServerBadgeTooltip"])
        }
    }

    private fun updateTftpClientLabel() {
        val running = TftpTransferManager.runningCount
        val visible = running > 0
        tftpClientLabel.isVisible = visible
        tftpClientLabel.isManaged = visible
        if (visible) {
            tftpClientLabel.text = Strings.format("status.tftpClientBadge", running)
            tftpClientLabel.tooltip = javafx.scene.control.Tooltip(Strings["status.tftpClientBadgeTooltip"])
        }
    }

    /**
     * Subscribe to connection-state events and show an [ErrorDialog] for failed connection attempts.
     * Mid-session errors (CONNECTED → ERROR) and disconnects keep flowing to the terminal as
     * "[error] …" lines so the user isn't carpet-bombed with dialogs while a server is misbehaving.
     */
    private fun subscribeConnectionErrorPopups() {
        com.opentermx.common.event.EventBus.subscribe { event ->
            if (event !is com.opentermx.common.event.ConnectionEvent.StateChanged) return@subscribe
            val cur = event.current
            val prev = event.previous
            val err = event.error ?: return@subscribe
            // Only popup when a connect attempt failed.
            val isConnectFailure = cur == ConnectionState.ERROR && prev == ConnectionState.CONNECTING
            if (!isConnectFailure) return@subscribe
            val sessionName = controllers.values.firstOrNull { it.session.id.value == event.sessionId }
                ?.session?.name ?: event.sessionId
            // Phase 2.5 T4: para fallas de negociación SSH (KEX/cipher/MAC/hostkey) le damos
            // al usuario un mensaje accionable + atajo a Setup → SSH General, en lugar de
            // mostrarle solo el `jschProposal=…,serverProposal=…` técnico.
            val tip = com.opentermx.app.ui.dialog.SshErrorTip.resolve(err.message, err)
            val friendly = tip?.let { Strings["error.ssh.tip." + it.name.lowercase()] }
            val actionLabel = if (tip?.opensSshGeneral == true) Strings["error.ssh.openSettings"] else null
            val onAction: (() -> Unit)? = if (tip?.opensSshGeneral == true) {
                { openSshGeneralConfig() }
            } else null
            javafx.application.Platform.runLater {
                ErrorDialog.error(
                    owner = stage,
                    title = Strings["error.connection.title"],
                    header = Strings.format("error.connection.header", sessionName),
                    message = err.message ?: err.javaClass.simpleName,
                    cause = err,
                    friendlyTip = friendly,
                    actionLabel = actionLabel,
                    onAction = onAction,
                )
            }
        }
    }

    private fun openTftpTransfersPanel() {
        val existing = tftpTransfersPanelRef
        if (existing != null && existing.isShowing) {
            existing.toFront()
            existing.requestFocus()
            return
        }
        val panel = TftpTransfersPanel(stage)
        tftpTransfersPanelRef = panel
        panel.show()
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
        rootPane.top = buildTopBar()
        refreshLabels()
    }

    private fun openFontDialog() {
        val dialog = FontConfigDialog(settings.terminalFontFamily, settings.terminalFontSize)
        val choice = dialog.showAndWait().orElse(null) ?: return
        persist { it.copy(terminalFontFamily = choice.family, terminalFontSize = choice.size) }
        applyFontToTerminals()
    }

    private fun openKeybindingsDialog() {
        val updated = KeybindingsDialog(settings.accelerators).showAndWait().orElse(null) ?: return
        persist { it.copy(accelerators = updated) }
        rebuildMenusAndLabels()
    }

    private fun openKeyboardConfig() {
        val updated = KeyboardDialog(settings.keyboard).showAndWait().orElse(null) ?: return
        persist { it.copy(keyboard = updated) }
        applyKeyboardSettingsToAll(updated)
    }

    private fun openScrollbackDialog() {
        val newLimit = ScrollbackDialog(settings.terminalScrollbackLimit).showAndWait().orElse(null) ?: return
        persist { it.copy(terminalScrollbackLimit = newLimit) }
        applyScrollbackToTerminals(newLimit)
    }

    private fun openTerminalConfig() {
        val updated = TerminalConfigDialog(settings.terminal).showAndWait().orElse(null) ?: return
        persist { it.copy(terminal = updated) }
        applyTerminalSettingsToAll(updated)
    }

    private fun openWindowConfig() {
        val previous = settings.window
        val tc = theme.terminalColors
        val updated = WindowConfigDialog(settings.window, tc.foreground, tc.background)
            .showAndWait().orElse(null) ?: return
        persist { it.copy(window = updated) }
        stage.opacity = updated.transparency
        stage.title = updated.titlePrefix.ifBlank { "COMPUHELP" }
        applyMouseCursorToAll(updated.mouseCursorMode)
        applyThemeToTerminals()
        if (updated.hideTitleBar != previous.hideTitleBar) {
            statusLabel.text = Strings["status.windowDecorationRestart"]
        }
    }

    private fun applyMouseCursorToAll(mode: String) {
        controllers.values.forEach { it.terminal.applyMouseCursor(mode) }
        forEachTerminal { it.applyMouseCursor(mode) }
    }

    private fun applyAdditionalSettingsToAll(a: com.opentermx.app.settings.AdditionalSettings) {
        val apply: (TerminalView) -> Unit = {
            it.applyAdditionalSettings(
                copyOnSelect = a.copyOnSelect,
                visualCursorBlink = a.visualCursorBlink,
                blinkText = a.blinkText,
            )
        }
        controllers.values.forEach { apply(it.terminal) }
        forEachTerminal(apply)
        // Phase 2.5 T3: el TelnetConnection lee esta system property en cada `connect()`
        // para decidir si registra el spy stream de IAC. Sesiones ya abiertas no se ven
        // afectadas — el flag aplica desde la próxima conexión.
        System.setProperty("opentermx.telnet.verboseLog", a.telnetVerboseLog.toString())
    }

    private fun openProxyConfig() {
        val updated = ProxyConfigDialog(settings.proxy).showAndWait().orElse(null) ?: return
        persist { it.copy(proxy = updated) }
    }

    private fun openSshGeneralConfig() {
        val updated = SshGeneralDialog(settings.sshGeneral).showAndWait().orElse(null) ?: return
        persist { it.copy(sshGeneral = updated) }
    }

    private fun openSshAuthConfig() {
        val updated = SshAuthDialog(settings.sshAuth).showAndWait().orElse(null) ?: return
        persist { it.copy(sshAuth = updated) }
    }

    private fun openSshKeyGenerator() {
        SshKeyGeneratorDialog(stage).show()
    }

    private fun openTcpIpConfig() {
        val updated = TcpIpConfigDialog(settings.tcpIp).showAndWait().orElse(null) ?: return
        persist { it.copy(tcpIp = updated) }
    }

    private fun openGeneralSettings() {
        val result = GeneralSettingsDialog(settings.locale, settings.general).showAndWait().orElse(null) ?: return
        val localeChanged = result.locale != settings.locale
        persist { it.copy(locale = result.locale, general = result.general) }
        if (localeChanged) {
            Strings.setLocale(result.locale)
            rebuildMenusAndLabels()
        }
    }

    private fun openAdditionalSettings() {
        val updated = AdditionalSettingsDialog(settings.additional).showAndWait().orElse(null) ?: return
        persist { it.copy(additional = updated) }
        applyAdditionalSettingsToAll(updated)
    }

    private fun bootMcpServerIfEnabled() {
        val launcher = com.opentermx.app.ui.mcp.MainWindowSessionLauncher(this)
        com.opentermx.app.ui.mcp.McpServerManager.configure(
            owner = { stage },
            settings = { settings.aiAssistant },
            sessionLauncher = { launcher },
            credentialStore = { com.opentermx.app.settings.SettingsCredentialStore { settings.aiAssistant } },
            appSettings = { settings },
        )
        com.opentermx.app.ui.mcp.McpServerManager.status()?.let { observeMcpStatus(it) }
        updateMcpServerLabel()
        if (!settings.aiAssistant.mcpServerEnabled) return
        com.opentermx.app.ui.mcp.McpServerManager.applySettings().exceptionOrNull()?.let { e ->
            statusLabel.text = "MCP: " + (e.message ?: e.javaClass.simpleName)
        }
        // El StateFlow se crea al primer start, así que reintentamos el subscribe aquí.
        com.opentermx.app.ui.mcp.McpServerManager.status()?.let { observeMcpStatus(it) }
        updateMcpServerLabel()
    }

    private var mcpStatusBinding: kotlinx.coroutines.Job? = null
    private fun observeMcpStatus(state: kotlinx.coroutines.flow.StateFlow<com.opentermx.mcp.McpServer.Status>) {
        mcpStatusBinding?.cancel()
        mcpStatusBinding = ioScope.launch {
            state.collect {
                javafx.application.Platform.runLater { updateMcpServerLabel() }
            }
        }
    }

    private fun updateMcpServerLabel() {
        val mgr = com.opentermx.app.ui.mcp.McpServerManager
        val current = mgr.status()?.value
        when (current) {
            null, com.opentermx.mcp.McpServer.Status.STOPPED -> {
                val show = settings.aiAssistant.mcpServerEnabled && current != null
                mcpServerLabel.isVisible = show
                mcpServerLabel.isManaged = show
                if (show) mcpServerLabel.text = Strings["status.mcp.off"]
            }
            com.opentermx.mcp.McpServer.Status.STARTING,
            com.opentermx.mcp.McpServer.Status.STOPPING -> {
                mcpServerLabel.isVisible = true; mcpServerLabel.isManaged = true
                mcpServerLabel.text = "MCP: " + current.name.lowercase()
                mcpServerLabel.tooltip = javafx.scene.control.Tooltip(Strings["status.mcp.tooltip"])
            }
            com.opentermx.mcp.McpServer.Status.RUNNING -> {
                mcpServerLabel.isVisible = true; mcpServerLabel.isManaged = true
                val binding = mgr.binding()
                val where = binding?.let { "${it.host}:${it.port}" } ?: "?"
                mcpServerLabel.text = Strings.format("status.mcp.running", where)
                mcpServerLabel.tooltip = javafx.scene.control.Tooltip(Strings["status.mcp.tooltip"])
            }
            com.opentermx.mcp.McpServer.Status.FAILED -> {
                mcpServerLabel.isVisible = true; mcpServerLabel.isManaged = true
                mcpServerLabel.text = Strings["status.mcp.failed"]
                val msg = mgr.lastError().orEmpty()
                mcpServerLabel.tooltip = javafx.scene.control.Tooltip(
                    Strings.format("status.mcp.tooltipFailed", msg)
                )
            }
        }
    }

    private var restApiListenerBound = false
    private fun bootRestApiIfEnabled() {
        RestApiManager.configure(
            RestApiHooksImpl(
                findControllerById = { id ->
                    controllers.values.firstOrNull { it.session.id.value == id }
                },
                getTftpDefaultCsvPath = { settings.additional.tftpCsvLogPath },
                aiBridge = com.opentermx.app.ui.ai.HeadlessMacroAiBridge { settings.aiAssistant },
            )
        )
        if (!restApiListenerBound) {
            RestApiManager.runningProperty.addListener { _, _, _ -> updateRestApiLabel() }
            restApiListenerBound = true
        }
        if (settings.restApi.enabled) {
            RestApiManager.applySettings(settings.restApi).exceptionOrNull()?.let { e ->
                statusLabel.text = Strings.format("status.restApi.error", e.message ?: e.javaClass.simpleName)
            }
        }
        updateRestApiLabel()
    }

    private fun openRestApiConfig() {
        val updated = RestApiDialog(settings.restApi)
            .also { it.initOwner(stage) }
            .showAndWait().orElse(null) ?: return
        persist { it.copy(restApi = updated) }
        val applied = RestApiManager.applySettings(updated)
        applied.exceptionOrNull()?.let { e ->
            statusLabel.text = Strings.format("status.restApi.error", e.message ?: e.javaClass.simpleName)
        }
        updateRestApiLabel()
    }

    private fun updateRestApiLabel() {
        val running = RestApiManager.isRunning
        restApiLabel.isVisible = running
        restApiLabel.isManaged = running
        if (running) {
            restApiLabel.text = Strings.format("status.restApi.running", RestApiManager.activePort)
            restApiLabel.tooltip = javafx.scene.control.Tooltip(Strings["status.restApi.tooltip"])
        }
    }

    private fun openAiAssistantConfig() {
        val updated = AiAssistantDialog(settings.aiAssistant)
            .also { it.initOwner(stage) }
            .showAndWait().orElse(null) ?: return
        persist { it.copy(aiAssistant = updated) }
        updateAiStatusLabel()
        if (::centerSplit.isInitialized && aiChatPanel in centerSplit.items) {
            aiChatPanel.refreshProviderLabel()
        }
        // Si cambiaron settings del MCP (enabled / port / bind / token), reconfigurar.
        com.opentermx.app.ui.mcp.McpServerManager.applySettings().exceptionOrNull()?.let { e ->
            statusLabel.text = "MCP: " + (e.message ?: e.javaClass.simpleName)
        }
        com.opentermx.app.ui.mcp.McpServerManager.status()?.let { observeMcpStatus(it) }
        updateMcpServerLabel()
    }

    private fun updateAiStatusLabel() {
        val ai = settings.aiAssistant
        if (!ai.isConfigured()) {
            aiStatusLabel.text = Strings["status.ai.notConfigured"]
            aiStatusLabel.tooltip = javafx.scene.control.Tooltip(Strings["status.ai.notConfiguredTooltip"])
            return
        }
        val providerName = ai.providerKind().name
        val model = ai.lastVerifiedModel ?: ai.modelFor(ai.providerKind()).orEmpty()
        aiStatusLabel.text = if (ai.isVerified()) {
            Strings.format("status.ai.connected", providerName, model)
        } else {
            Strings.format("status.ai.unverified", providerName)
        }
        aiStatusLabel.tooltip = javafx.scene.control.Tooltip(Strings["status.ai.tooltip"])
    }

    /**
     * Aplica el estado actual de `settings.additional.terminalOnlyMode` a la UI:
     * oculta o muestra las etiquetas IA/MCP/REST en la status bar, cierra el panel
     * de chat IA si quedó abierto, y detiene los servidores REST/MCP cuando se
     * bloquea. No reinicia los servicios al desbloquear — eso lo gestionan los
     * `bootXxxIfEnabled` que respetan los flags `enabled` de cada feature.
     */
    private fun applyTerminalOnlyMode() {
        val locked = settings.additional.terminalOnlyMode
        aiStatusLabel.isVisible = !locked
        aiStatusLabel.isManaged = !locked
        if (locked) {
            mcpServerLabel.isVisible = false; mcpServerLabel.isManaged = false
            restApiLabel.isVisible = false; restApiLabel.isManaged = false
            if (::centerSplit.isInitialized) setAiChatPanelVisible(false)
            com.opentermx.app.ui.mcp.McpServerManager.stop()
            RestApiManager.stop()
        } else {
            updateMcpServerLabel()
            updateRestApiLabel()
        }
    }

    /**
     * Flujo "Modo terminal": si está OFF pide un PIN nuevo y bloquea; si está ON
     * pide el PIN actual y desbloquea. El PIN se guarda hasheado (PBKDF2) en
     * `AdditionalSettings.terminalOnlyPinHash/Salt`. Reconstruye el menú al final
     * para que los ítems de IA/MCP/REST aparezcan o desaparezcan según corresponda.
     */
    private fun openTerminalOnlyModeConfig() {
        val current = settings.additional
        if (!current.terminalOnlyMode) {
            val hashed = com.opentermx.app.ui.dialog.TerminalOnlyModeDialog.askNewPin(stage) ?: return
            persist {
                it.copy(
                    additional = it.additional.copy(
                        terminalOnlyMode = true,
                        terminalOnlyPinHash = hashed.hashBase64,
                        terminalOnlyPinSalt = hashed.saltBase64,
                    )
                )
            }
            applyTerminalOnlyMode()
            rebuildMenusAndLabels()
            statusLabel.text = Strings["setup.terminalOnly.locked"]
        } else {
            val hash = current.terminalOnlyPinHash
            val salt = current.terminalOnlyPinSalt
            // Sin hash/salt persistidos (caso anómalo, p.ej. edición manual del JSON)
            // permitimos desbloquear sin verificación — preferible a dejar al usuario
            // bloqueado sin recuperación.
            val ok = if (hash != null && salt != null) {
                com.opentermx.app.ui.dialog.TerminalOnlyModeDialog.askExistingPin(stage, hash, salt)
            } else true
            if (!ok) return
            persist {
                it.copy(
                    additional = it.additional.copy(
                        terminalOnlyMode = false,
                        terminalOnlyPinHash = null,
                        terminalOnlyPinSalt = null,
                    )
                )
            }
            applyTerminalOnlyMode()
            rebuildMenusAndLabels()
            // Re-bootea los servicios deshabilitados por el lock anterior — sólo
            // arrancan si su `enabled` propio está en true, así que en la práctica
            // sólo despierta lo que el usuario ya quería corriendo.
            bootRestApiIfEnabled()
            bootMcpServerIfEnabled()
            statusLabel.text = Strings["setup.terminalOnly.unlocked"]
        }
    }

    private fun openSerialPortSetup() {
        val cfg = SerialConfigDialog().showAndWait().orElse(null) ?: return
        openSession(cfg, cfg.portName, SerialConnectionFactory.create(cfg, resolveSerialBackend()))
    }

    /**
     * El backend serial se resuelve con esta prioridad: (1) system property
     * `opentermx.serial.backend` si está fijada (override para tests/CLI);
     * (2) el valor persistido en `settings.additional.serialBackend`.
     */
    private fun resolveSerialBackend(): SerialConnectionFactory.Backend {
        val sys = System.getProperty(SerialConnectionFactory.BACKEND_PROPERTY)
        if (!sys.isNullOrBlank()) return SerialConnectionFactory.Backend.fromSystemProperty()
        return SerialConnectionFactory.Backend.fromName(settings.additional.serialBackend)
    }

    /**
     * Selecciona el motor VT del `TerminalView` con la misma cadena de precedencia que
     * `resolveSerialBackend`: (1) system property `opentermx.terminal.engine` si está fijada;
     * (2) `settings.additional.terminalEngine`. Si el motor nativo no carga, `TerminalView`
     * hace fallback silencioso a Kotlin.
     */
    private fun resolveTerminalEngine(): TerminalEngine {
        val sys = System.getProperty("opentermx.terminal.engine")?.trim()?.lowercase()
        if (!sys.isNullOrBlank()) {
            return if (sys == "native") TerminalEngine.NATIVE else TerminalEngine.KOTLIN
        }
        return if (settings.additional.terminalEngine.equals("NATIVE", ignoreCase = true)) {
            TerminalEngine.NATIVE
        } else {
            TerminalEngine.KOTLIN
        }
    }

    private fun saveSetup() {
        val file = FileChooser().apply {
            title = Strings["setup.saveSetup"]
            initialFileName = "opentermx-setup.json"
            extensionFilters.add(FileChooser.ExtensionFilter("JSON", "*.json"))
            initialDirectory = SettingsStore.configDir.toFile().takeIf { it.isDirectory }
        }.showSaveDialog(stage) ?: return
        val activeConfig = currentController()?.session?.config
        val snapshot = com.opentermx.app.settings.SnapshotConverters.build(settings, activeConfig)
        runCatching { SettingsStore.exportSnapshot(snapshot, file) }
            .onSuccess {
                val msg = if (snapshot.savedSession != null)
                    Strings.format("status.setupSavedWithSession",
                        file.absolutePath, snapshot.savedSession.displayName)
                else
                    Strings.format("status.setupSaved", file.absolutePath)
                statusLabel.text = msg
            }
            .onFailure { statusLabel.text = Strings.format("status.setupError", it.message ?: "") }
    }

    private fun restoreSetup() {
        val file = FileChooser().apply {
            title = Strings["setup.restoreSetup"]
            extensionFilters.add(FileChooser.ExtensionFilter("JSON", "*.json"))
            initialDirectory = SettingsStore.configDir.toFile().takeIf { it.isDirectory }
        }.showOpenDialog(stage) ?: return
        val snapshot = runCatching { SettingsStore.importSnapshot(file) }
            .onFailure {
                log.warn("Restore setup failed", it)
                statusLabel.text = Strings.format("status.setupError", it.message ?: "")
            }
            .getOrNull() ?: return

        val imported = snapshot.settings
        settings = imported
        SettingsStore.save(imported)
        Strings.setLocale(imported.locale)
        theme.applyTo(rootPane.scene!!)
        applyFontToTerminals()
        applyScrollbackToTerminals(imported.terminalScrollbackLimit)
        applyThemeToTerminals()
        applyTerminalSettingsToAll(imported.terminal)
        applyKeyboardSettingsToAll(imported.keyboard)
        applyAdditionalSettingsToAll(imported.additional)
        stage.opacity = imported.window.transparency
        rebuildMenusAndLabels()
        statusLabel.text = Strings.format("status.setupRestored", file.absolutePath)

        snapshot.savedSession?.let { saved ->
            val confirm = Alert(Alert.AlertType.CONFIRMATION).apply {
                title = Strings["setup.restoreSession.title"]
                headerText = Strings.format("setup.restoreSession.header", saved.displayName)
                contentText = Strings["setup.restoreSession.body"]
            }.showAndWait()
            if (confirm.isPresent && confirm.get() == javafx.scene.control.ButtonType.OK) {
                openSavedSession(saved)
            }
        }
    }

    private fun openSavedSession(saved: com.opentermx.app.settings.SavedSession) {
        val cfg = com.opentermx.app.settings.SnapshotConverters.configFromSession(saved, settings)
        if (cfg == null) {
            statusLabel.text = Strings.format("status.setupError",
                "Unsupported saved session type: ${saved.type}")
            return
        }
        when (cfg) {
            is SshConfig -> openSession(cfg, "${cfg.username}@${cfg.host}", SshConnection(cfg, hostKeyVerifier))
            is TelnetConfig -> {
                val protocol = if (cfg.useTls) "telnets" else "telnet"
                openSession(cfg, "$protocol://${cfg.host}:${cfg.port}", TelnetConnection(cfg))
            }
            is TcpRawConfig -> openSession(cfg, "${cfg.host}:${cfg.port}", RawTcpConnection(cfg))
            is SerialConfig -> openSession(cfg, cfg.portName, SerialConnectionFactory.create(cfg, resolveSerialBackend()))
        }
    }

    private fun showSetupDirectory() {
        val dir = SettingsStore.configDir.toFile()
        if (!dir.isDirectory) dir.mkdirs()
        runCatching { java.awt.Desktop.getDesktop().open(dir) }
            .onFailure { log.info("Desktop.open not available; just reporting path", it) }
        statusLabel.text = Strings.format("status.setupDirOpen", dir.absolutePath)
    }

    private fun loadKeyMap() {
        val file = FileChooser().apply {
            title = Strings["setup.loadKeymap"]
            extensionFilters.addAll(
                FileChooser.ExtensionFilter("Key map", "*.keymap", "*.properties"),
                FileChooser.ExtensionFilter("All files", "*.*"),
            )
        }.showOpenDialog(stage) ?: return
        runCatching {
            val props = java.util.Properties()
            file.bufferedReader(Charsets.UTF_8).use { props.load(it) }
            val merged = settings.accelerators.toMutableMap()
            var n = 0
            for (key in props.stringPropertyNames()) {
                val value = props.getProperty(key).trim()
                if (value.isEmpty()) merged.remove(key) else merged[key] = value
                n++
            }
            persist { it.copy(accelerators = merged) }
            rebuildMenusAndLabels()
            n
        }.onSuccess { count ->
            statusLabel.text = Strings.format("status.keymapLoaded", count)
        }.onFailure {
            log.warn("Load key map failed", it)
            statusLabel.text = Strings.format("status.setupError", it.message ?: "")
        }
    }

    private fun applyScrollbackToTerminals(limit: Int) {
        controllers.values.forEach { it.terminal.applyScrollbackLimit(limit) }
        forEachTerminal { it.applyScrollbackLimit(limit) }
    }

    private fun applyThemeToTerminals() {
        val c = effectiveTerminalColors()
        controllers.values.forEach { it.terminal.applyColors(c.foreground, c.background, c.cursor, c.selection) }
        // Welcome and any extra terminals not tracked in controllers
        forEachTerminal { it.applyColors(c.foreground, c.background, c.cursor, c.selection) }
    }

    /**
     * Resolves the effective terminal palette: theme provides cursor/selection (and the fg/bg
     * defaults), while the user's `WindowSettings.terminalForeground`/`terminalBackground`
     * overrides win for fg/bg when present. A blank override means "follow the theme".
     */
    private fun effectiveTerminalColors(): TerminalColors {
        val themeColors = theme.terminalColors
        val w = settings.window
        val fg = parseHex(w.terminalForeground) ?: themeColors.foreground
        val bg = parseHex(w.terminalBackground) ?: themeColors.background
        return TerminalColors(fg, bg, themeColors.cursor, themeColors.selection)
    }

    private fun parseHex(hex: String): javafx.scene.paint.Color? =
        if (hex.isBlank()) null else runCatching { javafx.scene.paint.Color.web(hex) }.getOrNull()

    private fun applyFontToTerminals() {
        controllers.values.forEach { it.terminal.applyFont(settings.terminalFontFamily, settings.terminalFontSize) }
        forEachTerminal { it.applyFont(settings.terminalFontFamily, settings.terminalFontSize) }
    }

    private fun forEachTerminal(action: (TerminalView) -> Unit) {
        for (tab in tabPane.tabs) {
            (tab.content as? TerminalView)?.let(action)
        }
    }

    /**
     * Wraps the tabPane in a StackPane that hosts a mouse-transparent watermark on top.
     * Sitting at the MainWindow level (not inside any TerminalView), the watermark is
     * positioned by JavaFX layout independently of any per-terminal canvas, scroll, or
     * repaint — so it stays anchored to the window centre regardless of terminal activity.
     */
    private fun buildCenterWithWatermark(): Region {
        val tabsArea: Region = run {
            val image = runCatching {
                javaClass.getResourceAsStream("/images/Compuhelp.png")?.use { Image(it) }
            }.getOrNull()
            if (image == null) {
                tabPane
            } else {
                val watermark = ImageView(image).apply {
                    opacity = 0.25
                    isMouseTransparent = true
                    isPreserveRatio = true
                }
                StackPane(tabPane, watermark).apply {
                    StackPane.setAlignment(tabPane, javafx.geometry.Pos.CENTER)
                    StackPane.setAlignment(watermark, javafx.geometry.Pos.CENTER)
                    watermark.fitWidthProperty().bind(widthProperty().multiply(0.5))
                    watermark.fitHeightProperty().bind(heightProperty().multiply(0.5))
                    // Permitir que el centro encoja sin pisar la barra de estado/menú del
                    // BorderPane cuando la ventana es pequeña — sin esto la imagen del
                    // watermark imponía un min-height que recortaba el bottom.
                    minHeight = 0.0
                    minWidth = 0.0
                }
            }
        }
        centerSplit = javafx.scene.control.SplitPane().apply {
            orientation = javafx.geometry.Orientation.HORIZONTAL
            items.add(tabsArea)
        }
        return centerSplit
    }

    private lateinit var centerSplit: javafx.scene.control.SplitPane

    private val aiChatPanel: AiChatPanel by lazy {
        AiChatPanel(
            getSettings = { settings.aiAssistant },
            onSettingsUpdated = { updated ->
                persist { it.copy(aiAssistant = updated) }
                updateAiStatusLabel()
            },
            getTerminalContext = { buildAiTerminalContext() },
            getCommandSink = {
                currentController()?.session?.id?.let {
                    com.opentermx.common.ai.SessionRegistry.sinkOf(it)
                }
            },
            onClose = { setAiChatPanelVisible(false) },
        ).also { panel ->
            panel.openSetupCallback = { openAiAssistantConfig() }
        }
    }

    private fun toggleAiChatPanel() {
        if (!::centerSplit.isInitialized) return
        val visible = aiChatPanel in centerSplit.items
        setAiChatPanelVisible(!visible)
    }

    private fun setAiChatPanelVisible(visible: Boolean) {
        if (!::centerSplit.isInitialized) return
        if (visible) {
            if (aiChatPanel !in centerSplit.items) {
                centerSplit.items += aiChatPanel
                centerSplit.setDividerPositions(0.7)
            }
            aiChatPanel.refreshProviderLabel()
        } else {
            centerSplit.items.remove(aiChatPanel)
        }
    }

    private fun buildAiTerminalContext(): com.opentermx.app.ui.ai.TerminalContextSnapshot? {
        val ctl = currentController() ?: return null
        val id = ctl.session.id
        val meta = com.opentermx.common.ai.SessionRegistry.metadataOf(id) ?: return null
        val lines = com.opentermx.common.ai.SessionRegistry.lastLinesOf(id, 50)
        return com.opentermx.app.ui.ai.TerminalContextSnapshot(
            sessionId = id.value,
            protocol = meta.protocol,
            host = meta.host,
            port = meta.port,
            username = meta.username,
            terminalLines = lines,
        )
    }

    /**
     * Top bar = brand label "COMPUHELP" + the standard MenuBar. The brand sits in the
     * upper-left of the application window so it's visible regardless of the OS title bar
     * (which is also driven by `WindowSettings.titlePrefix`).
     */
    private fun buildTopBar(): Region {
        val brand = Label("COMPUHELP").apply {
            styleClass += "brand-label"
            padding = Insets(4.0, 14.0, 4.0, 14.0)
            style = "-fx-font-weight: bold; -fx-font-size: 14px;"
        }
        val menuBar = buildMenuBar()
        HBox.setHgrow(menuBar, Priority.ALWAYS)
        return HBox(brand, menuBar).apply {
            alignment = Pos.CENTER_LEFT
            styleClass += "top-bar"
        }
    }

    private fun buildStatusBar(): Region {
        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        val separator = Separator(javafx.geometry.Orientation.VERTICAL)
        return HBox(12.0, statusLabel, spacer, restApiLabel, mcpServerLabel, aiStatusLabel, tftpClientLabel, tftpServerLabel, protocolLabel, separator, themeLabel).apply {
            styleClass += "status-bar"
            alignment = Pos.CENTER_LEFT
            minHeight = 26.0
        }
    }

    private fun openWelcomeTab() {
        val terminal = newTerminal()
        terminal.append(Strings["welcome.line1"] + "\n")
        terminal.append(Strings["welcome.line2"] + "\n")
        terminal.onInput = { input -> terminal.append(input) }
        val tab = Tab(Strings["welcome.tabTitle"], terminal).apply { isClosable = true }
        tab.setOnClosed { terminal.dispose() }
        tabPane.tabs += tab
        tabPane.selectionModel.select(tab)
    }

    private fun newTerminal(): TerminalView {
        val terminal = TerminalView(
            fontFamily = settings.terminalFontFamily,
            fontSize = settings.terminalFontSize,
            scrollbackLimit = settings.terminalScrollbackLimit,
            initialCols = settings.terminal.cols,
            initialRows = settings.terminal.rows,
            engine = resolveTerminalEngine(),
        )
        val c = effectiveTerminalColors()
        terminal.applyColors(c.foreground, c.background, c.cursor, c.selection)
        terminal.applyTerminalSettings(
            cursorStyle = settings.terminal.cursorStyle,
            cursorBlink = settings.terminal.cursorBlink,
            encoding = settings.terminal.encoding,
            newlineMode = settings.terminal.newlineMode,
            localEcho = settings.terminal.localEcho,
            scrollMode = settings.terminal.scrollMode,
        )
        terminal.applyKeyboardSettings(
            backspaceSendsDel = settings.keyboard.backspaceSendsDel,
            deleteSendsBs = settings.keyboard.deleteSendsBs,
            metaSendsEscape = settings.keyboard.metaSendsEscape,
        )
        // Resaltado contextual: el TerminalView consulta los settings via callback (lee
        // siempre la versión actual de `settings.highlight` sin necesidad de re-aplicar).
        terminal.setHighlightSettingsProvider { settings.highlight }
        terminal.applyAdditionalSettings(
            copyOnSelect = settings.additional.copyOnSelect,
            visualCursorBlink = settings.additional.visualCursorBlink,
            blinkText = settings.additional.blinkText,
        )
        terminal.applyMouseCursor(settings.window.mouseCursorMode)
        return terminal
    }

    private fun applyTerminalSettingsToAll(t: com.opentermx.app.settings.TerminalSettings) {
        val apply: (TerminalView) -> Unit = {
            it.applyTerminalSettings(
                cursorStyle = t.cursorStyle,
                cursorBlink = t.cursorBlink,
                encoding = t.encoding,
                newlineMode = t.newlineMode,
                localEcho = t.localEcho,
                scrollMode = t.scrollMode,
            )
        }
        controllers.values.forEach { apply(it.terminal) }
        forEachTerminal(apply)
    }

    private fun applyKeyboardSettingsToAll(k: com.opentermx.app.settings.KeyboardSettings) {
        val apply: (TerminalView) -> Unit = {
            it.applyKeyboardSettings(
                backspaceSendsDel = k.backspaceSendsDel,
                deleteSendsBs = k.deleteSendsBs,
                metaSendsEscape = k.metaSendsEscape,
            )
        }
        controllers.values.forEach { apply(it.terminal) }
        forEachTerminal(apply)
    }

    private fun openPortForwardDialog() {
        val ctl = currentController()
        val conn = ctl?.session?.connection
        if (conn !is SshConnection || ctl.state.value != ConnectionState.CONNECTED) {
            statusLabel.text = Strings["status.pfRequiresSsh"]
            return
        }
        PortForwardDialog(conn).showAndWait()
    }

    private fun openSftpForCurrentSession() {
        val ctl = currentController()
        val conn = ctl?.session?.connection
        if (conn !is SshConnection || ctl.state.value != ConnectionState.CONNECTED) {
            statusLabel.text = Strings["status.sftpRequiresSsh"]
            return
        }
        val panel = SftpPanel(conn)
        val tab = Tab("SFTP — ${ctl.session.name}", panel).apply { isClosable = true }
        tab.setOnClosed { panel.shutdown() }
        tabPane.tabs += tab
        tabPane.selectionModel.select(tab)
    }

    private fun openNewConnectionDialog() {
        val dialog = NewConnectionDialog(
            recentHosts = settings.recentHosts,
            historyEnabled = settings.historyEnabled,
        )
        val choice = dialog.showAndWait().orElse(null)
        // The "History" checkbox state is preserved even when the user cancels —
        // it's a global preference, not part of this connection's data.
        persist { it.copy(historyEnabled = dialog.isHistoryEnabled()) }
        if (choice == null) return

        when (choice) {
            is NewConnectionChoice.Serial -> {
                val seed = SerialConfig(portName = choice.port.systemPortName)
                val cfg = SerialConfigDialog(seed).showAndWait().orElse(null) ?: return
                openSession(cfg, cfg.portName, SerialConnectionFactory.create(cfg, resolveSerialBackend()))
            }
            is NewConnectionChoice.Ssh -> {
                if (choice.sshVersion == SshVersion.SSH1) {
                    ErrorDialog.warning(
                        owner = stage,
                        title = Strings["nc.ssh1Title"],
                        header = Strings["nc.ssh1Header"],
                        message = Strings["nc.ssh1Body"],
                    )
                    return
                }
                val saved = com.opentermx.app.settings.SavedConnections.findMostRecent(
                    settings.savedConnections, protocol = "SSH", host = choice.host, port = choice.tcpPort,
                )
                val seed = seedSshConfig(choice.host, choice.tcpPort, saved)
                val dialog = SshConfigDialog(
                    seed,
                    rememberCredentialsDefault = saved != null,
                    initialLabel = saved?.label.orEmpty(),
                )
                val cfg = dialog.showAndWait().orElse(null) ?: return
                val label = dialog.labelField.text?.trim().orEmpty()
                persistSavedConnectionDecision(cfg, dialog.rememberCredentialsCheck.isSelected, label)
                val tabName = label.ifBlank { "${cfg.username}@${cfg.host}" }
                openSession(cfg, tabName, SshConnection(cfg, hostKeyVerifier))
                rememberHost(choice.host)
            }
            is NewConnectionChoice.Telnet -> {
                val saved = com.opentermx.app.settings.SavedConnections.findMostRecent(
                    settings.savedConnections, protocol = "TELNET", host = choice.host, port = choice.tcpPort,
                )
                val seedCfg = TelnetConfig(
                    host = choice.host,
                    port = choice.tcpPort,
                    terminalType = settings.tcpIp.terminalType,
                    keepAlive = settings.tcpIp.keepAlive,
                    recvBufferSize = settings.tcpIp.recvBufferSize,
                    proxy = currentProxyConfig(),
                    dnsMode = settings.tcpIp.dnsMode,
                )
                val dialog = com.opentermx.app.ui.dialog.TelnetConfigDialog(
                    initial = seedCfg,
                    initialLabel = saved?.label.orEmpty(),
                    initialUsername = saved?.username.orEmpty(),
                    rememberDefault = saved != null,
                )
                val confirmed = dialog.showAndWait().orElse(null) ?: return
                val cfg = seedCfg.copy(
                    host = confirmed.host,
                    port = confirmed.port,
                    useTls = confirmed.useTls,
                )
                val label = dialog.labelField.text?.trim().orEmpty()
                val refUser = dialog.usernameField.text?.trim().orEmpty()
                persistSavedTelnet(cfg, dialog.rememberCheck.isSelected, label, refUser)
                val tabName = label.ifBlank {
                    val protocol = if (cfg.useTls) "telnets" else "telnet"
                    "$protocol://${cfg.host}:${cfg.port}"
                }
                openSession(cfg, tabName, TelnetConnection(cfg))
                rememberHost(choice.host)
            }
            is NewConnectionChoice.TcpRaw -> {
                val cfg = TcpRawConfig(
                    host = choice.host,
                    port = choice.tcpPort,
                    keepAlive = settings.tcpIp.keepAlive,
                    recvBufferSize = settings.tcpIp.recvBufferSize,
                    proxy = currentProxyConfig(),
                    dnsMode = settings.tcpIp.dnsMode,
                )
                openSession(cfg, "${cfg.host}:${cfg.port}", RawTcpConnection(cfg))
                rememberHost(choice.host)
            }
        }
    }

    /**
     * Builds an SshConfig prefilled from `saved` (entrada recordada para este host:port) si
     * existe, sino desde Setup → SSH / SSH Authentication / TCP-IP. El usuario puede sobreescribir
     * cualquier campo en el [SshConfigDialog]; los campos que el dialog no renderiza pasan
     * sin cambios.
     */
    private fun seedSshConfig(
        host: String,
        port: Int,
        saved: com.opentermx.app.settings.SavedConnection? = null,
    ): SshConfig {
        val auth = settings.sshAuth
        val gen = settings.sshGeneral
        val username = saved?.username?.takeIf { it.isNotBlank() } ?: auth.defaultUsername
        val authObj: SshAuth = when {
            saved?.authKind == com.opentermx.app.settings.SavedAuthKind.PASSWORD -> {
                val plain = saved.secret?.let { runCatching { com.opentermx.common.crypto.SecretCipher.decrypt(it) }.getOrNull() }
                SshAuth.Password((plain ?: "").toCharArray())
            }
            saved?.authKind == com.opentermx.app.settings.SavedAuthKind.SSH_KEY && !saved.keyPath.isNullOrBlank() -> {
                val passphrase = saved.secret?.let { runCatching { com.opentermx.common.crypto.SecretCipher.decrypt(it) }.getOrNull() }
                SshAuth.PublicKey(saved.keyPath, passphrase?.takeIf { it.isNotEmpty() }?.toCharArray())
            }
            auth.method == "PUBLIC_KEY" && auth.privateKeyPath.isNotBlank() -> SshAuth.PublicKey(auth.privateKeyPath)
            else -> SshAuth.Password(CharArray(0))
        }
        return SshConfig(
            host = host,
            username = username,
            auth = authObj,
            port = port,
            keepAliveSeconds = gen.heartbeatSeconds,
            agentForwarding = false,
            tryAgentFirst = auth.tryAgentFirst,
            portForwards = emptyList(),
            compression = gen.compression,
            ciphers = gen.ciphers,
            kex = gen.kex,
            macs = gen.macs,
            terminalType = settings.tcpIp.terminalType,
            proxy = currentProxyConfig(),
        )
    }

    private fun currentProxyConfig(): ProxyConfig {
        val p = settings.proxy
        val type = runCatching { ProxyConfig.Type.valueOf(p.type) }.getOrDefault(ProxyConfig.Type.NONE)
        return ProxyConfig(
            type = type,
            host = p.host,
            port = p.port,
            username = p.username,
            password = p.password,
        )
    }

    private fun openSavedConnectionsDialog() {
        val dialog = com.opentermx.app.ui.dialog.SavedConnectionsDialog(settings.savedConnections)
        val updated = dialog.showAndWait().orElse(null) ?: return
        if (updated != settings.savedConnections) {
            persist { it.copy(savedConnections = updated) }
            savedConnectionsListView.refresh()
        }
    }

    /**
     * Abre el dialog de configuración del resaltado. Al confirmar, persiste el nuevo
     * `HighlightSettings` y dispara un repaint manual de todos los terminales (con
     * `dirty=true`) para que los toggles cambien apenas se vea sin esperar nueva data.
     */
    private fun openHighlightConfigDialog() {
        val dialog = com.opentermx.app.ui.dialog.HighlightConfigDialog(settings.highlight)
        dialog.initOwner(stage)
        val updated = dialog.showAndWait().orElse(null) ?: return
        if (updated != settings.highlight) {
            persist { it.copy(highlight = updated) }
            // Forzar repaint en cada tab abierta (TerminalView lee `settings.highlight` vía
            // su provider, pero el cache del engine es por-instancia: re-instalamos el
            // provider que internamente invalida el cache y dispara `dirty=true`).
            controllers.values.forEach { ctl ->
                ctl.terminal.setHighlightSettingsProvider { settings.highlight }
            }
        }
    }

    /**
     * Menu File → Nueva sesión Web. Abre [com.opentermx.app.ui.dialog.WebConfigDialog] para
     * que el operador entre URL, etiqueta y credenciales; al confirmar, abre un tab con
     * `WebSessionView` y, si tildó "Recordar", persiste un `SavedConnection(protocol="WEB")`.
     */
    private fun openNewWebSessionDialog(initial: com.opentermx.app.ui.dialog.WebConfigDialog.Result? = null) {
        val dialog = com.opentermx.app.ui.dialog.WebConfigDialog(
            initialUrl = initial?.url.orEmpty(),
            initialLabel = initial?.label.orEmpty(),
            initialUsername = initial?.username.orEmpty(),
            initialPassword = initial?.password.orEmpty(),
            autofillDefault = initial?.autofill ?: true,
            rememberDefault = initial?.remember ?: false,
        )
        val result = dialog.showAndWait().orElse(null) ?: return
        persistSavedWeb(result)
        openWebSession(result.url, result.label, result.username, result.password, result.autofill)
    }

    /**
     * Abre un tab con [com.opentermx.app.ui.web.WebSessionView]. Antes de cargar la primera
     * URL https, instala (idempotente) un SSLContext trust-all para que los certs auto-firmados
     * de routers/switches no rompan el load. Trade-off de seguridad documentado en
     * [installTrustAllSslOnce].
     */
    private fun openWebSession(
        url: String,
        label: String,
        autofillUser: String,
        autofillPass: String,
        autofill: Boolean,
    ) {
        installTrustAllSslOnce()
        val view = com.opentermx.app.ui.web.WebSessionView(
            initialUrl = url,
            autofillUsername = autofillUser,
            autofillPassword = autofillPass,
            autofill = autofill,
        )
        val tab = Tab().apply {
            text = label.ifBlank { url }
            content = view
            isClosable = true
            setOnClosed { view.dispose() }
        }
        tabPane.tabs += tab
        tabPane.selectionModel.select(tab)
    }

    /**
     * Persiste/elimina una entrada WEB siguiendo la misma simetría que SSH/Telnet: si el
     * usuario tildó "Recordar", upsert; si destildó y había entries, removeByHost (port = 0).
     * `host` lleva la URL completa (porque `protocol = "WEB"` no usa el campo `port`).
     */
    private fun persistSavedWeb(result: com.opentermx.app.ui.dialog.WebConfigDialog.Result) {
        val current = settings.savedConnections
        val updated = if (result.remember) {
            val secret = result.password.takeIf { it.isNotEmpty() }
                ?.let { com.opentermx.common.crypto.SecretCipher.encrypt(it) }
            val entry = com.opentermx.app.settings.SavedConnection(
                id = java.util.UUID.randomUUID().toString(),
                protocol = "WEB",
                host = result.url,
                port = 0,
                username = result.username,
                authKind = if (secret != null) com.opentermx.app.settings.SavedAuthKind.PASSWORD
                    else com.opentermx.app.settings.SavedAuthKind.NONE,
                secret = secret,
                label = result.label,
            )
            com.opentermx.app.settings.SavedConnections.upsert(current, entry, bumpLastUsed = true)
        } else {
            com.opentermx.app.settings.SavedConnections.removeByHost(
                current, protocol = "WEB", host = result.url, port = 0,
            )
        }
        if (updated != current) {
            persist { it.copy(savedConnections = updated) }
            savedConnectionsListView.refresh()
        }
    }

    /**
     * Abre el dialog para crear una sesión RDP. Solo soportado en Windows — en otro OS
     * mostramos un alert y salimos. Al confirmar, persiste (si tildó "Recordar") y lanza
     * `mstsc.exe` con la cred previamente registrada en Credential Manager. La ventana
     * RDP es nativa y NO un tab — eso es una limitación de la integración
     * (ver explicación en el chat / commit message).
     */
    private fun openNewRdpSessionDialog(initial: com.opentermx.app.ui.dialog.RdpConfigDialog.Result? = null) {
        if (!com.opentermx.app.ui.rdp.RdpLauncher.isSupported) {
            javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.WARNING,
                "Las sesiones RDP usan mstsc.exe (cliente nativo de Windows). Esta funcionalidad " +
                    "solo está disponible en Windows.",
            ).apply {
                title = "RDP no disponible"
                headerText = "Sistema operativo no soportado"
                initOwner(stage)
            }.showAndWait()
            return
        }
        val dialog = com.opentermx.app.ui.dialog.RdpConfigDialog(
            initialHost = initial?.host.orEmpty(),
            initialPort = initial?.port ?: com.opentermx.app.ui.rdp.RdpLauncher.DEFAULT_RDP_PORT,
            initialLabel = initial?.label.orEmpty(),
            initialUsername = initial?.username.orEmpty(),
            initialPassword = initial?.password.orEmpty(),
            rememberDefault = initial?.remember ?: false,
        )
        val result = dialog.showAndWait().orElse(null) ?: return
        persistSavedRdp(result)
        launchRdpSession(result.host, result.port, result.username, result.password)
    }

    /** Lanza mstsc.exe con la cred ya en Credential Manager. */
    private fun launchRdpSession(host: String, port: Int, username: String, password: String) {
        val ok = com.opentermx.app.ui.rdp.RdpLauncher.launch(host, port, username, password)
        if (!ok) {
            statusLabel.text = "Fallo al lanzar mstsc.exe (ver log)"
        }
    }

    /**
     * Persiste o elimina una entrada RDP siguiendo la simetría on/off de SSH/Telnet/WEB.
     * `host` lleva el hostname/IP; `port` el TCP port; password cifrada en `secret`.
     */
    private fun persistSavedRdp(result: com.opentermx.app.ui.dialog.RdpConfigDialog.Result) {
        val current = settings.savedConnections
        val updated = if (result.remember) {
            val secret = result.password.takeIf { it.isNotEmpty() }
                ?.let { com.opentermx.common.crypto.SecretCipher.encrypt(it) }
            val entry = com.opentermx.app.settings.SavedConnection(
                id = java.util.UUID.randomUUID().toString(),
                protocol = "RDP",
                host = result.host,
                port = result.port,
                username = result.username,
                authKind = if (secret != null) com.opentermx.app.settings.SavedAuthKind.PASSWORD
                    else com.opentermx.app.settings.SavedAuthKind.NONE,
                secret = secret,
                label = result.label,
            )
            com.opentermx.app.settings.SavedConnections.upsert(current, entry, bumpLastUsed = true)
        } else {
            // Si destildó "Recordar", también limpiamos la cred del Credential Manager si quedó.
            com.opentermx.app.ui.rdp.RdpLauncher.deleteCredential(result.host)
            com.opentermx.app.settings.SavedConnections.removeByHost(
                current, protocol = "RDP", host = result.host, port = result.port,
            )
        }
        if (updated != current) {
            persist { it.copy(savedConnections = updated) }
            savedConnectionsListView.refresh()
        }
    }

    @Volatile private var sslTrustAllInstalled: Boolean = false

    /**
     * Instala un SSLContext "trust-all" una sola vez por proceso. Justificación: los admin UIs
     * de routers/switches casi siempre traen cert auto-firmado y `WebView` no expone API para
     * trust per-instancia. Trade-off: cualquier llamada HTTPS saliente del proceso (provider
     * IA, knowledge base download, etc.) deja de validar el cert. Aceptable para esta
     * herramienta de administración local pero NO para un entorno con tráfico sensible
     * (banca, OAuth de producción, etc.).
     */
    private fun installTrustAllSslOnce() {
        if (sslTrustAllInstalled) return
        synchronized(this) {
            if (sslTrustAllInstalled) return
            try {
                val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
                })
                val ctx = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                    init(null, trustAll, java.security.SecureRandom())
                }
                javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(ctx.socketFactory)
                javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
                javax.net.ssl.SSLContext.setDefault(ctx)
                sslTrustAllInstalled = true
                log.warn("SSL trust-all instalado para WebView (admin UIs con cert auto-firmado). " +
                    "Cualquier HTTPS saliente de este proceso deja de validar cert.")
            } catch (t: Throwable) {
                log.warn("No se pudo instalar SSL trust-all: {}", t.message)
            }
        }
    }

    /**
     * Edita una entrada guardada desde el panel lateral (clic-derecho → Modificar…). Reusa el
     * dialog correspondiente al protocolo, pre-cargado con los valores actuales. Al confirmar,
     * reemplaza la entrada vieja por una nueva con los campos actualizados (mismo id) — no abre
     * tab nuevo.
     */
    private fun editSavedConnection(saved: com.opentermx.app.settings.SavedConnection) {
        when (saved.protocol) {
            "SSH" -> editSshSaved(saved)
            "TELNET" -> editTelnetSaved(saved)
            "WEB" -> editWebSaved(saved)
            "RDP" -> editRdpSaved(saved)
            else -> log.warn("editSavedConnection: protocolo no soportado '${saved.protocol}'")
        }
    }

    private fun editRdpSaved(saved: com.opentermx.app.settings.SavedConnection) {
        val currentPlain = saved.secret
            ?.takeIf { saved.authKind == com.opentermx.app.settings.SavedAuthKind.PASSWORD }
            ?.let { runCatching { com.opentermx.common.crypto.SecretCipher.decrypt(it) }.getOrNull() }
            .orEmpty()
        val dialog = com.opentermx.app.ui.dialog.RdpConfigDialog(
            initialHost = saved.host,
            initialPort = saved.port,
            initialLabel = saved.label,
            initialUsername = saved.username,
            initialPassword = currentPlain,
            rememberDefault = true,
        )
        val result = dialog.showAndWait().orElse(null) ?: return
        val withoutOld = com.opentermx.app.settings.SavedConnections.removeById(settings.savedConnections, saved.id)
        // Si el host cambió, limpiar cred vieja del Credential Manager.
        if (!result.host.equals(saved.host, ignoreCase = true)) {
            com.opentermx.app.ui.rdp.RdpLauncher.deleteCredential(saved.host)
        }
        val newSecret = result.password.takeIf { it.isNotEmpty() }
            ?.let { com.opentermx.common.crypto.SecretCipher.encrypt(it) }
        val newEntry = com.opentermx.app.settings.SavedConnection(
            id = java.util.UUID.randomUUID().toString(),
            protocol = "RDP",
            host = result.host,
            port = result.port,
            username = result.username,
            authKind = if (newSecret != null) com.opentermx.app.settings.SavedAuthKind.PASSWORD
                else com.opentermx.app.settings.SavedAuthKind.NONE,
            secret = newSecret,
            label = result.label,
        )
        val updated = com.opentermx.app.settings.SavedConnections.upsert(withoutOld, newEntry, bumpLastUsed = false)
        persist { it.copy(savedConnections = updated) }
        savedConnectionsListView.refresh()
    }

    private fun editSshSaved(saved: com.opentermx.app.settings.SavedConnection) {
        val seed = seedSshConfig(saved.host, saved.port, saved)
        val dialog = SshConfigDialog(seed, rememberCredentialsDefault = true, initialLabel = saved.label)
        val cfg = dialog.showAndWait().orElse(null) ?: return
        val newLabel = dialog.labelField.text?.trim().orEmpty()
        // Sacamos primero la entrada vieja (por id) para evitar duplicar si user cambió host/port.
        val withoutOld = com.opentermx.app.settings.SavedConnections.removeById(settings.savedConnections, saved.id)
        val newEntry = buildSavedConnectionFrom(cfg, newLabel)
        val updated = com.opentermx.app.settings.SavedConnections.upsert(withoutOld, newEntry, bumpLastUsed = false)
        persist { it.copy(savedConnections = updated) }
        savedConnectionsListView.refresh()
    }

    private fun editTelnetSaved(saved: com.opentermx.app.settings.SavedConnection) {
        val seedCfg = TelnetConfig(
            host = saved.host,
            port = saved.port,
            terminalType = settings.tcpIp.terminalType,
            keepAlive = settings.tcpIp.keepAlive,
            recvBufferSize = settings.tcpIp.recvBufferSize,
            proxy = currentProxyConfig(),
            dnsMode = settings.tcpIp.dnsMode,
        )
        val dialog = com.opentermx.app.ui.dialog.TelnetConfigDialog(
            initial = seedCfg,
            initialLabel = saved.label,
            initialUsername = saved.username,
            rememberDefault = true,
        )
        val confirmed = dialog.showAndWait().orElse(null) ?: return
        val withoutOld = com.opentermx.app.settings.SavedConnections.removeById(settings.savedConnections, saved.id)
        val newEntry = com.opentermx.app.settings.SavedConnection(
            id = java.util.UUID.randomUUID().toString(),
            protocol = "TELNET",
            host = confirmed.host,
            port = confirmed.port,
            username = dialog.usernameField.text?.trim().orEmpty(),
            authKind = com.opentermx.app.settings.SavedAuthKind.NONE,
            label = dialog.labelField.text?.trim().orEmpty(),
        )
        val updated = com.opentermx.app.settings.SavedConnections.upsert(withoutOld, newEntry, bumpLastUsed = false)
        persist { it.copy(savedConnections = updated) }
        savedConnectionsListView.refresh()
    }

    private fun editWebSaved(saved: com.opentermx.app.settings.SavedConnection) {
        val currentPlain = saved.secret
            ?.takeIf { saved.authKind == com.opentermx.app.settings.SavedAuthKind.PASSWORD }
            ?.let { runCatching { com.opentermx.common.crypto.SecretCipher.decrypt(it) }.getOrNull() }
            .orEmpty()
        val dialog = com.opentermx.app.ui.dialog.WebConfigDialog(
            initialUrl = saved.host,
            initialLabel = saved.label,
            initialUsername = saved.username,
            initialPassword = currentPlain,
            autofillDefault = true,
            rememberDefault = true,
        )
        val result = dialog.showAndWait().orElse(null) ?: return
        val withoutOld = com.opentermx.app.settings.SavedConnections.removeById(settings.savedConnections, saved.id)
        val newSecret = result.password.takeIf { it.isNotEmpty() }
            ?.let { com.opentermx.common.crypto.SecretCipher.encrypt(it) }
        val newEntry = com.opentermx.app.settings.SavedConnection(
            id = java.util.UUID.randomUUID().toString(),
            protocol = "WEB",
            host = result.url,
            port = 0,
            username = result.username,
            authKind = if (newSecret != null) com.opentermx.app.settings.SavedAuthKind.PASSWORD
                else com.opentermx.app.settings.SavedAuthKind.NONE,
            secret = newSecret,
            label = result.label,
        )
        val updated = com.opentermx.app.settings.SavedConnections.upsert(withoutOld, newEntry, bumpLastUsed = false)
        persist { it.copy(savedConnections = updated) }
        savedConnectionsListView.refresh()
    }

    /**
     * Borra una entrada guardada desde el panel lateral (clic-derecho → Eliminar o tecla DELETE).
     * Para entries RDP también limpia la cred del Credential Manager de Windows — simetría
     * on/off: borrar la entrada de OpenTermX también la quita del SO.
     */
    private fun deleteSavedConnection(saved: com.opentermx.app.settings.SavedConnection) {
        if (saved.protocol == "RDP") {
            com.opentermx.app.ui.rdp.RdpLauncher.deleteCredential(saved.host)
        }
        val updated = com.opentermx.app.settings.SavedConnections.removeById(settings.savedConnections, saved.id)
        if (updated != settings.savedConnections) {
            persist { it.copy(savedConnections = updated) }
            savedConnectionsListView.refresh()
        }
    }

    /**
     * Conecta directo desde el panel "Conexiones guardadas" (doble-click). Arma el config
     * sin pasar por el dialog interactivo — todas las credenciales ya están en `saved`. Tras
     * conectar, bumpea `lastUsedAtMillis` para que la entrada suba al tope del listado.
     */
    private fun quickConnectSaved(saved: com.opentermx.app.settings.SavedConnection) {
        when (saved.protocol) {
            "SSH" -> {
                val seed = seedSshConfig(saved.host, saved.port, saved)
                val tabName = saved.displayLabel()
                openSession(seed, tabName, SshConnection(seed, hostKeyVerifier))
            }
            "TELNET" -> {
                val cfg = TelnetConfig(
                    host = saved.host,
                    port = saved.port,
                    terminalType = settings.tcpIp.terminalType,
                    keepAlive = settings.tcpIp.keepAlive,
                    recvBufferSize = settings.tcpIp.recvBufferSize,
                    proxy = currentProxyConfig(),
                    dnsMode = settings.tcpIp.dnsMode,
                )
                openSession(cfg, saved.displayLabel(), TelnetConnection(cfg))
            }
            "WEB" -> {
                // `host` lleva la URL completa; `port` es 0 (no se usa).
                val plaintext = saved.secret
                    ?.takeIf { saved.authKind == com.opentermx.app.settings.SavedAuthKind.PASSWORD }
                    ?.let { runCatching { com.opentermx.common.crypto.SecretCipher.decrypt(it) }.getOrNull() }
                    .orEmpty()
                openWebSession(
                    url = saved.host,
                    label = saved.displayLabel(),
                    autofillUser = saved.username,
                    autofillPass = plaintext,
                    autofill = true,
                )
            }
            "RDP" -> {
                if (!com.opentermx.app.ui.rdp.RdpLauncher.isSupported) {
                    statusLabel.text = "RDP solo disponible en Windows"
                    return
                }
                val plaintext = saved.secret
                    ?.takeIf { saved.authKind == com.opentermx.app.settings.SavedAuthKind.PASSWORD }
                    ?.let { runCatching { com.opentermx.common.crypto.SecretCipher.decrypt(it) }.getOrNull() }
                    .orEmpty()
                launchRdpSession(saved.host, saved.port, saved.username, plaintext)
            }
            else -> {
                log.warn("quickConnect: protocolo no soportado '${saved.protocol}' para ${saved.host}")
                return
            }
        }
        // Bump del timestamp para que la entrada quede al tope del listado.
        val refreshed = com.opentermx.app.settings.SavedConnections.upsert(
            settings.savedConnections, saved, bumpLastUsed = true,
        )
        if (refreshed != settings.savedConnections) {
            persist { it.copy(savedConnections = refreshed) }
            savedConnectionsListView.refresh()
        }
        rememberHost(saved.host)
    }

    private fun rememberHost(host: String) {
        if (!settings.historyEnabled || host.isBlank()) return
        // Most-recent first, dedup case-insensitive, cap at 20.
        val deduped = (listOf(host) + settings.recentHosts.filterNot { it.equals(host, ignoreCase = true) })
            .take(20)
        persist { it.copy(recentHosts = deduped) }
    }

    /**
     * Persiste o elimina la `SavedConnection` para (cfg.host, cfg.port, cfg.username) según la
     * decisión del checkbox "Recordar credenciales" del [SshConfigDialog].
     *  - `remember = true`: cifra password/passphrase con SecretCipher, upsertea con timestamp.
     *  - `remember = false`: si había entradas para `(host, port)` las elimina (el usuario
     *    explícitamente destildó). Mantenemos la simetría: tildar/destildar = on/off.
     */
    /**
     * Persiste o elimina una entrada Telnet. Telnet no tiene auth en el protocolo, así que
     * `authKind = NONE` y no hay secret ni keyPath. `username` se guarda como referencia para
     * que el operador recuerde con qué login entra (no se transmite).
     */
    private fun persistSavedTelnet(cfg: TelnetConfig, remember: Boolean, label: String, username: String) {
        val current = settings.savedConnections
        val updated = if (remember) {
            val entry = com.opentermx.app.settings.SavedConnection(
                id = java.util.UUID.randomUUID().toString(),
                protocol = "TELNET",
                host = cfg.host,
                port = cfg.port,
                username = username,
                authKind = com.opentermx.app.settings.SavedAuthKind.NONE,
                label = label,
            )
            com.opentermx.app.settings.SavedConnections.upsert(current, entry, bumpLastUsed = true)
        } else {
            com.opentermx.app.settings.SavedConnections.removeByHost(
                current, protocol = "TELNET", host = cfg.host, port = cfg.port,
            )
        }
        if (updated != current) {
            persist { it.copy(savedConnections = updated) }
            savedConnectionsListView.refresh()
        }
    }

    private fun persistSavedConnectionDecision(cfg: SshConfig, remember: Boolean, label: String) {
        val current = settings.savedConnections
        val updated = if (remember) {
            val entry = buildSavedConnectionFrom(cfg, label)
            com.opentermx.app.settings.SavedConnections.upsert(current, entry, bumpLastUsed = true)
        } else {
            com.opentermx.app.settings.SavedConnections.removeByHost(
                current, protocol = "SSH", host = cfg.host, port = cfg.port,
            )
        }
        if (updated != current) {
            persist { it.copy(savedConnections = updated) }
            savedConnectionsListView.refresh()
        }
    }

    private fun buildSavedConnectionFrom(cfg: SshConfig, label: String): com.opentermx.app.settings.SavedConnection {
        val (kind, secret, keyPath) = when (val a = cfg.auth) {
            is SshAuth.Password -> {
                val pw = String(a.password)
                Triple(
                    com.opentermx.app.settings.SavedAuthKind.PASSWORD,
                    if (pw.isEmpty()) null else com.opentermx.common.crypto.SecretCipher.encrypt(pw),
                    null,
                )
            }
            is SshAuth.PublicKey -> {
                val pp = a.passphrase?.let { String(it) }.orEmpty()
                Triple(
                    com.opentermx.app.settings.SavedAuthKind.SSH_KEY,
                    if (pp.isEmpty()) null else com.opentermx.common.crypto.SecretCipher.encrypt(pp),
                    a.privateKeyPath,
                )
            }
        }
        return com.opentermx.app.settings.SavedConnection(
            id = java.util.UUID.randomUUID().toString(),
            protocol = "SSH",
            host = cfg.host,
            port = cfg.port,
            username = cfg.username,
            authKind = kind,
            secret = secret,
            keyPath = keyPath,
            label = label,
        )
    }

    private fun openSession(config: ConnectionConfig, name: String, connection: Connection): SessionId {
        val session = Session(SessionId.random(), name, config, connection)
        val terminal = newTerminal()
        terminal.append("→ ${config.displayName}\n")

        val controller = TerminalSessionController(session, terminal)
        val tab = Tab().apply {
            content = terminal
            isClosable = true
        }
        bindTabTitle(tab, controller)
        tab.setOnClosed {
            stopController(tab)
            terminal.dispose()
        }

        controllers[tab] = controller
        tabPane.tabs += tab
        tabPane.selectionModel.select(tab)
        viewModel.addSession(session)

        // Auto-start a session log on the first CONNECTED transition if requested,
        // and run the auto-login macro if one is configured.
        controller.state.addListener { _, old, new ->
            if (new == ConnectionState.CONNECTED && old != ConnectionState.CONNECTED) {
                maybeAutoLog(controller)
                maybeAutoLogin(controller)
            }
        }
        controller.start()
        return session.id
    }

    /**
     * API headless: abre una sesión programáticamente sin pasar por el diálogo "New Connection".
     * Pensada para la tool MCP `open_session` (T9). Si se llama desde fuera del FX thread, postea
     * la apertura con [javafx.application.Platform.runLater] y bloquea hasta tener el `SessionId`.
     * Devuelve el id resultante para que el caller pueda registrarlo / devolverlo al cliente.
     */
    fun launchSession(config: ConnectionConfig, name: String, connection: Connection): SessionId {
        if (javafx.application.Platform.isFxApplicationThread()) {
            return openSession(config, name, connection)
        }
        val future = java.util.concurrent.CompletableFuture<SessionId>()
        javafx.application.Platform.runLater {
            try {
                future.complete(openSession(config, name, connection))
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return future.get()
    }

    fun hostKeyVerifier(): com.opentermx.common.connection.HostKeyVerifier = hostKeyVerifier

    /**
     * Runs the configured auto-login macro against this session right after CONNECTED. The
     * macro path comes from Setup → Additional… (autoLoginMacroPath); a missing or unreadable
     * file is reported in the status bar but doesn't break the session.
     */
    private fun maybeAutoLogin(ctl: TerminalSessionController) {
        val perSession = when (val cfg = ctl.session.config) {
            is SshConfig -> cfg.autoLoginMacroPath
            is TelnetConfig -> cfg.autoLoginMacroPath
            is TcpRawConfig -> cfg.autoLoginMacroPath
            is SerialConfig -> cfg.autoLoginMacroPath
        }
        val path = perSession.ifBlank { settings.additional.autoLoginMacroPath }
        if (path.isBlank()) return
        val file = java.io.File(path)
        if (!file.isFile) {
            statusLabel.text = Strings.format("status.autoLoginMissing", path)
            return
        }
        ioScope.launch {
            try {
                val script = file.readText(Charsets.UTF_8)
                val engine = com.opentermx.macro.MacroEngine()
                val bridge = MacroUiBridgeImpl()
                engine.start(script, ctl.session.connection, ctl.session.id.value, bridge, macroAiBridge) { /* drop log entries */ }
            } catch (e: Exception) {
                log.warn("Auto-login macro fallido", e)
                javafx.application.Platform.runLater {
                    statusLabel.text = Strings.format("status.autoLoginError", e.message ?: e.javaClass.simpleName)
                }
            }
        }
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
        val a = settings.additional
        val cfg = LogConfigDialog(
            suggestedName = ctl.session.name,
            defaultDir = a.defaultLogDir,
            defaultFormat = a.defaultLogFormat,
            defaultTimestamps = a.defaultLogTimestamps,
            defaultTimestampPattern = a.defaultLogTimestampPattern,
            defaultRotation = a.defaultLogRotation,
            defaultRotationSizeMb = a.defaultLogRotationSizeMb,
            defaultRotationMinutes = a.defaultLogRotationMinutes,
        ).showAndWait().orElse(null) ?: return
        runCatching { LogManager.start(ctl.session.id.value, cfg) }
            .onSuccess { statusLabel.text = Strings.format("status.logActive", cfg.basePath) }
            .onFailure {
                log.warn("No se pudo iniciar log", it)
                statusLabel.text = Strings.format("status.captureError", it.message ?: "")
            }
    }

    /**
     * If Setup → Additional → Log → "Iniciar log al conectar" is checked, opens a session log
     * with the configured defaults (no dialog) the first time the connection reaches CONNECTED.
     * The path is built from the session name to keep concurrent sessions separate.
     */
    private fun maybeAutoLog(ctl: TerminalSessionController) {
        val a = settings.additional
        if (!a.autoLogOnConnect) return
        if (LogManager.isActive(ctl.session.id.value)) return
        val safeName = ctl.session.name.replace(Regex("[^A-Za-z0-9_.@-]"), "_")
        val ts = java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val format = runCatching {
            com.opentermx.logger.LogFormat.valueOf(a.defaultLogFormat.uppercase())
        }.getOrDefault(com.opentermx.logger.LogFormat.PLAIN)
        val ext = when (format) {
            com.opentermx.logger.LogFormat.HTML -> "html"
            com.opentermx.logger.LogFormat.RAW -> "bin"
            else -> "log"
        }
        val path = java.nio.file.Paths.get(a.defaultLogDir, "$safeName-$ts.$ext")
        val rotation: com.opentermx.logger.RotationPolicy = when (a.defaultLogRotation.uppercase()) {
            "BY_SIZE" -> com.opentermx.logger.RotationPolicy.BySize(a.defaultLogRotationSizeMb.toLong() * 1024 * 1024)
            "BY_TIME" -> com.opentermx.logger.RotationPolicy.ByTime(a.defaultLogRotationMinutes.toLong() * 60_000L)
            else -> com.opentermx.logger.RotationPolicy.None
        }
        val cfg = com.opentermx.logger.LogConfig(
            basePath = path,
            format = format,
            timestamps = a.defaultLogTimestamps,
            timestampPattern = a.defaultLogTimestampPattern.ifBlank { "yyyy-MM-dd HH:mm:ss.SSS" },
            rotation = rotation,
        )
        runCatching {
            java.nio.file.Files.createDirectories(path.parent ?: path)
            LogManager.start(ctl.session.id.value, cfg)
        }.onSuccess { statusLabel.text = Strings.format("status.logActive", cfg.basePath) }
            .onFailure { log.warn("Auto-log fallido", it) }
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

    private fun openSerialSignals() {
        val ctl = currentController()
        val conn = ctl?.session?.connection
        if (conn !is SerialPortConnection || ctl.state.value != ConnectionState.CONNECTED) {
            statusLabel.text = Strings["status.serialSignalsRequires"]
            return
        }
        SerialSignalsDialog(stage, conn).show()
    }

    private fun sendBreakOnCurrent() {
        val ctl = currentController() ?: return
        val conn = ctl.session.connection
        if (conn is SerialPortConnection) {
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
        updateTftpServerLabel()
        updateTftpClientLabel()
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