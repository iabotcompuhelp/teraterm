package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.EdgeAgentSettings
import com.opentermx.common.crypto.EncryptedValue
import com.opentermx.common.crypto.SecretCipher
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.control.Spinner
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.stage.Window

class AgentConfigDialog(
    private val owner: Window?,
    private val initial: EdgeAgentSettings,
) {
    private val enabled = CheckBox(Strings["agent.config.enabled"]).apply { isSelected = initial.enabled == true }
    private val url = TextField(initial.controlPlaneUrl).apply { promptText = "https://opentermx.example:8766" }
    private val agentId = TextField(initial.agentId).apply { promptText = "noc-win-01" }
    private val displayName = TextField(initial.displayName).apply { promptText = "Consola NOC" }
    private val heartbeat = Spinner<Int>(2, 60, initial.heartbeatSeconds.toInt().coerceIn(2, 60))
    private val stateDir = TextField(initial.stateDirectory).apply { promptText = "~/.opentermx/agent" }
    private val token = PasswordField().apply { promptText = if (EncryptedValue.isEmpty(initial.token)) Strings["agent.config.tokenNew"] else Strings["agent.config.tokenKeep"] }
    private val feedback = Label().apply { isWrapText = true }

    fun showAndWait(): EdgeAgentSettings? {
        val save = ButtonType(Strings["agent.config.save"], ButtonBar.ButtonData.OK_DONE)
        val dialog = Dialog<EdgeAgentSettings?>().apply {
            title = Strings["agent.config.title"]
            headerText = Strings["agent.config.header"]
            if (owner != null) initOwner(owner)
            dialogPane.buttonTypes.setAll(save, ButtonType.CANCEL)
            dialogPane.content = content()
            isResizable = true
            setResultConverter { button -> if (button == save) buildSettings() else null }
        }
        val saveButton = dialog.dialogPane.lookupButton(save)
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION) { event ->
            val error = validateInput()
            if (error != null) { feedback.text = error; event.consume() }
        }
        return dialog.showAndWait().orElse(null)
    }

    private fun content(): GridPane {
        val test = Button(Strings["agent.config.test"]).apply { setOnAction { testConnection() } }
        return GridPane().apply {
            padding = Insets(12.0); hgap = 10.0; vgap = 9.0
            var row = 0
            add(enabled, 0, row++, 2, 1)
            add(Label(Strings["agent.config.url"]), 0, row); add(url, 1, row++)
            add(Label(Strings["agent.config.id"]), 0, row); add(agentId, 1, row++)
            add(Label(Strings["agent.config.name"]), 0, row); add(displayName, 1, row++)
            add(Label(Strings["agent.config.heartbeat"]), 0, row); add(heartbeat, 1, row++)
            add(Label(Strings["agent.config.stateDir"]), 0, row); add(stateDir, 1, row++)
            add(Label(Strings["agent.config.token"]), 0, row); add(token, 1, row++)
            add(test, 0, row); add(feedback, 1, row)
        }
    }

    internal fun buildSettings(): EdgeAgentSettings {
        val encrypted = token.text.takeIf { it.isNotBlank() }?.let(SecretCipher::encrypt) ?: initial.token
        return EdgeAgentSettings(
            enabled = enabled.isSelected,
            controlPlaneUrl = url.text.trim(),
            agentId = agentId.text.trim(),
            displayName = displayName.text.trim(),
            heartbeatSeconds = heartbeat.value.toLong(),
            stateDirectory = stateDir.text.trim(),
            token = encrypted,
        )
    }

    private fun validateInput(): String? {
        if (!enabled.isSelected) return null
        return runCatching { buildSettings().runtimeConfig(emptyMap()) }.exceptionOrNull()?.message
    }

    private fun testConnection() {
        val error = validateInput()
        if (error != null) { feedback.text = error; return }
        feedback.text = Strings["agent.config.testing"]
        val settings = buildSettings()
        CompletableFuture.runAsync {
            val config = settings.runtimeConfig(emptyMap()) ?: error("Agente deshabilitado")
            val health = URI(config.gatewayUri.toString().substringBefore("/agent/v1/heartbeat") + "/agent/v1/health")
            val request = HttpRequest.newBuilder(health).timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer ${config.token}").GET().build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding())
            check(response.statusCode() == 200) { "HTTP ${response.statusCode()}" }
        }.whenComplete { _, failure -> Platform.runLater {
            feedback.text = if (failure == null) Strings["agent.config.testOk"]
                else Strings.format("agent.config.testFailed", failure.cause?.message ?: failure.message.orEmpty())
        } }
    }
}
