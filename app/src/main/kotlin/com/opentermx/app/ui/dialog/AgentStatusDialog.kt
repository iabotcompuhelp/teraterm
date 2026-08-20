package com.opentermx.app.ui.dialog

import com.opentermx.agent.AgentConnectionState
import com.opentermx.agent.AgentTaskHistoryEntry
import com.opentermx.app.agent.EdgeAgentManager
import com.opentermx.app.i18n.Strings
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.layout.GridPane
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Window
import javafx.util.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AgentStatusDialog(private val owner: Window?) {
    private val stateValue = Label()
    private val agentValue = Label()
    private val gatewayValue = Label().apply { isWrapText = true }
    private val heartbeatValue = Label()
    private val sessionsValue = Label()
    private val taskValue = Label()
    private val errorValue = Label().apply { isWrapText = true; style = "-fx-text-fill: #d9534f;" }
    private val history = TableView<AgentTaskHistoryEntry>()
    private val actionButton = Button()
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    fun show() {
        val dialog = Dialog<Unit>().apply {
            title = Strings["agent.status.title"]
            headerText = Strings["agent.status.header"]
            if (owner != null) initOwner(owner)
            dialogPane.buttonTypes += ButtonType.CLOSE
            dialogPane.content = buildContent()
            isResizable = true
            dialogPane.prefWidth = 760.0
            dialogPane.prefHeight = 520.0
        }
        val timer = Timeline(
            KeyFrame(Duration.seconds(1.0), javafx.event.EventHandler { refresh() }),
        ).apply {
            cycleCount = Timeline.INDEFINITE
        }
        actionButton.setOnAction {
            val state = EdgeAgentManager.status().value.state
            if (state == AgentConnectionState.STOPPED || state == AgentConnectionState.DISABLED) {
                EdgeAgentManager.restart()
            } else {
                EdgeAgentManager.stop()
            }
            refresh()
        }
        refresh()
        timer.play()
        dialog.showAndWait()
        timer.stop()
    }

    private fun buildContent(): VBox {
        val grid = GridPane().apply {
            hgap = 12.0; vgap = 8.0
            addRow(0, Label(Strings["agent.status.state"]), stateValue)
            addRow(1, Label(Strings["agent.status.identity"]), agentValue)
            addRow(2, Label(Strings["agent.status.gateway"]), gatewayValue)
            addRow(3, Label(Strings["agent.status.heartbeat"]), heartbeatValue)
            addRow(4, Label(Strings["agent.status.sessions"]), sessionsValue)
            addRow(5, Label(Strings["agent.status.currentTask"]), taskValue)
            addRow(6, Label(Strings["agent.status.lastError"]), errorValue)
        }
        val taskColumn = TableColumn<AgentTaskHistoryEntry, String>(Strings["agent.status.task"]).apply {
            prefWidth = 220.0; setCellValueFactory { SimpleStringProperty(it.value.taskId) }
        }
        val statusColumn = TableColumn<AgentTaskHistoryEntry, String>(Strings["agent.status.result"]).apply {
            prefWidth = 110.0; setCellValueFactory { SimpleStringProperty(it.value.status.name) }
        }
        val timeColumn = TableColumn<AgentTaskHistoryEntry, String>(Strings["agent.status.completed"]).apply {
            prefWidth = 165.0; setCellValueFactory { SimpleStringProperty(format(it.value.completedAtMillis)) }
        }
        val countColumn = TableColumn<AgentTaskHistoryEntry, String>(Strings["agent.status.executed"]).apply {
            prefWidth = 90.0; setCellValueFactory { SimpleStringProperty(it.value.executedCount.toString()) }
        }
        history.columns.setAll(taskColumn, statusColumn, timeColumn, countColumn)
        history.placeholder = Label(Strings["agent.status.noHistory"])
        VBox.setVgrow(history, Priority.ALWAYS)
        return VBox(12.0, grid, actionButton, Label(Strings["agent.status.history"]), history).apply {
            padding = Insets(12.0)
        }
    }

    private fun refresh() {
        val status = EdgeAgentManager.status().value
        stateValue.text = status.state.name
        agentValue.text = listOfNotNull(status.displayName, status.agentId?.let { "($it)" }).joinToString(" ").ifBlank { "—" }
        gatewayValue.text = status.gateway ?: "—"
        heartbeatValue.text = status.lastHeartbeatMillis?.let(::format) ?: "—"
        sessionsValue.text = status.activeSessions.toString()
        taskValue.text = status.currentTaskId ?: "—"
        errorValue.text = status.lastError ?: "—"
        history.items = FXCollections.observableArrayList(status.history)
        val stopped = status.state == AgentConnectionState.STOPPED || status.state == AgentConnectionState.DISABLED
        actionButton.text = if (stopped) Strings["agent.status.reconnect"] else Strings["agent.status.disconnect"]
        actionButton.isDisable = !EdgeAgentManager.isConfigured()
    }

    private fun format(millis: Long): String = formatter.format(Instant.ofEpochMilli(millis))
}
