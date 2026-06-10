package com.opentermx.app.ui

import com.opentermx.app.i18n.Strings
import com.opentermx.app.rest.RestApiManager
import com.opentermx.app.settings.AppSettings
import com.opentermx.app.ui.tftp.TftpServerManager
import com.opentermx.app.ui.tftp.TftpTransferManager
import com.opentermx.app.viewmodel.TerminalSessionController
import javafx.beans.binding.Bindings
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.Separator
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Status bar inferior de la ventana principal: estado general, protocolo de la sesión
 * activa, tema, y badges clickeables de TFTP server/client, IA, REST y MCP.
 * Extraído de `MainWindow` (split 2026-06). Esta clase es dueña de los labels y de
 * sus reglas de visibilidad/texto; las acciones de click se inyectan como lambdas.
 */
class StatusBarView(
    private val settings: () -> AppSettings,
    private val ioScope: CoroutineScope,
    onOpenTftpServerDialog: () -> Unit,
    onOpenTftpTransfersPanel: () -> Unit,
    onOpenAiAssistantConfig: () -> Unit,
    onOpenRestApiConfig: () -> Unit,
) {

    val statusLabel = Label()
    private val protocolLabel = Label()
    private val themeLabel = Label()

    private val tftpServerLabel = Label().apply {
        cursor = javafx.scene.Cursor.HAND
        styleClass += "status-tftp"
        setOnMouseClicked { onOpenTftpServerDialog() }
        isVisible = false
        isManaged = false
    }
    private val tftpClientLabel = Label().apply {
        cursor = javafx.scene.Cursor.HAND
        styleClass += "status-tftp"
        setOnMouseClicked { onOpenTftpTransfersPanel() }
        isVisible = false
        isManaged = false
    }
    private val aiStatusLabel = Label().apply {
        cursor = javafx.scene.Cursor.HAND
        styleClass += "status-ai"
        setOnMouseClicked { onOpenAiAssistantConfig() }
    }
    private val restApiLabel = Label().apply {
        cursor = javafx.scene.Cursor.HAND
        styleClass += "status-rest"
        setOnMouseClicked { onOpenRestApiConfig() }
        isVisible = false; isManaged = false
    }
    private val mcpServerLabel = Label().apply {
        cursor = javafx.scene.Cursor.HAND
        styleClass += "status-mcp"
        setOnMouseClicked { onOpenAiAssistantConfig() }
        isVisible = false; isManaged = false
    }

    fun build(): Region {
        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        val separator = Separator(javafx.geometry.Orientation.VERTICAL)
        return HBox(12.0, statusLabel, spacer, restApiLabel, mcpServerLabel, aiStatusLabel, tftpClientLabel, tftpServerLabel, protocolLabel, separator, themeLabel).apply {
            styleClass += "status-bar"
            alignment = Pos.CENTER_LEFT
            minHeight = 26.0
        }
    }

    /** Bindea el label de protocolo a la sesión del tab activo (o "sin conexión"). */
    fun bindProtocolTo(ctl: TerminalSessionController?) {
        protocolLabel.textProperty().unbind()
        if (ctl == null) {
            protocolLabel.text = Strings["status.noConnection"]
        } else {
            protocolLabel.textProperty().bind(Bindings.createStringBinding({
                "${ctl.session.config.displayName} — ${ctl.state.value}"
            }, ctl.state))
        }
    }

    fun setThemeLabel(isDark: Boolean) {
        themeLabel.text = if (isDark) Strings["status.themeDark"] else Strings["status.themeLight"]
    }

    fun updateTftpServerLabel() {
        val running = TftpServerManager.isRunning
        tftpServerLabel.isVisible = running
        tftpServerLabel.isManaged = running
        if (running) {
            tftpServerLabel.text = Strings.format("status.tftpServerBadge", TftpServerManager.actualPort)
            tftpServerLabel.tooltip = Tooltip(Strings["status.tftpServerBadgeTooltip"])
        }
    }

    fun updateTftpClientLabel() {
        val running = TftpTransferManager.runningCount
        val visible = running > 0
        tftpClientLabel.isVisible = visible
        tftpClientLabel.isManaged = visible
        if (visible) {
            tftpClientLabel.text = Strings.format("status.tftpClientBadge", running)
            tftpClientLabel.tooltip = Tooltip(Strings["status.tftpClientBadgeTooltip"])
        }
    }

    fun updateAiStatusLabel() {
        val ai = settings().aiAssistant
        if (!ai.isConfigured()) {
            aiStatusLabel.text = Strings["status.ai.notConfigured"]
            aiStatusLabel.tooltip = Tooltip(Strings["status.ai.notConfiguredTooltip"])
            return
        }
        val providerName = ai.providerKind().name
        val model = ai.lastVerifiedModel ?: ai.modelFor(ai.providerKind()).orEmpty()
        aiStatusLabel.text = if (ai.isVerified()) {
            Strings.format("status.ai.connected", providerName, model)
        } else {
            Strings.format("status.ai.unverified", providerName)
        }
        aiStatusLabel.tooltip = Tooltip(Strings["status.ai.tooltip"])
    }

    fun updateRestApiLabel() {
        val running = RestApiManager.isRunning
        restApiLabel.isVisible = running
        restApiLabel.isManaged = running
        if (running) {
            restApiLabel.text = Strings.format("status.restApi.running", RestApiManager.activePort)
            restApiLabel.tooltip = Tooltip(Strings["status.restApi.tooltip"])
        }
    }

    fun updateMcpServerLabel() {
        val mgr = com.opentermx.app.ui.mcp.McpServerManager
        val current = mgr.status()?.value
        when (current) {
            null, com.opentermx.mcp.McpServer.Status.STOPPED -> {
                val show = settings().aiAssistant.mcpServerEnabled && current != null
                mcpServerLabel.isVisible = show
                mcpServerLabel.isManaged = show
                if (show) mcpServerLabel.text = Strings["status.mcp.off"]
            }
            com.opentermx.mcp.McpServer.Status.STARTING,
            com.opentermx.mcp.McpServer.Status.STOPPING -> {
                mcpServerLabel.isVisible = true; mcpServerLabel.isManaged = true
                mcpServerLabel.text = "MCP: " + current.name.lowercase()
                mcpServerLabel.tooltip = Tooltip(Strings["status.mcp.tooltip"])
            }
            com.opentermx.mcp.McpServer.Status.RUNNING -> {
                mcpServerLabel.isVisible = true; mcpServerLabel.isManaged = true
                val binding = mgr.binding()
                val where = binding?.let { "${it.host}:${it.port}" } ?: "?"
                mcpServerLabel.text = Strings.format("status.mcp.running", where)
                mcpServerLabel.tooltip = Tooltip(Strings["status.mcp.tooltip"])
            }
            com.opentermx.mcp.McpServer.Status.FAILED -> {
                mcpServerLabel.isVisible = true; mcpServerLabel.isManaged = true
                mcpServerLabel.text = Strings["status.mcp.failed"]
                val msg = mgr.lastError().orEmpty()
                mcpServerLabel.tooltip = Tooltip(
                    Strings.format("status.mcp.tooltipFailed", msg)
                )
            }
        }
    }

    private var mcpStatusBinding: Job? = null

    /** (Re)suscribe el label MCP al StateFlow de estado del server. */
    fun observeMcpStatus(state: StateFlow<com.opentermx.mcp.McpServer.Status>) {
        mcpStatusBinding?.cancel()
        mcpStatusBinding = ioScope.launch {
            state.collect {
                javafx.application.Platform.runLater { updateMcpServerLabel() }
            }
        }
    }

    /**
     * Visibilidad de los badges IA/MCP/REST según el modo terminal (PIN lock):
     * bloqueado los oculta todos; desbloqueado re-evalúa cada uno con su update.
     */
    fun applyTerminalOnlyVisibility(locked: Boolean) {
        aiStatusLabel.isVisible = !locked
        aiStatusLabel.isManaged = !locked
        if (locked) {
            mcpServerLabel.isVisible = false; mcpServerLabel.isManaged = false
            restApiLabel.isVisible = false; restApiLabel.isManaged = false
        } else {
            updateMcpServerLabel()
            updateRestApiLabel()
        }
    }
}
