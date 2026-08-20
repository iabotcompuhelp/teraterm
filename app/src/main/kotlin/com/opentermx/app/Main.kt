package com.opentermx.app

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.SettingsStore
import com.opentermx.app.ui.MainWindow
import com.opentermx.app.viewmodel.AppViewModel
import javafx.application.Application
import javafx.stage.Stage
import com.opentermx.agent.EdgeAgentConfig
import com.opentermx.agent.WindowsEdgeAgent
import org.slf4j.LoggerFactory
import com.opentermx.app.ui.ai.ApprovedRemoteTaskProcessor
import com.opentermx.app.ui.ai.JavaFxApprovalGate

class OpenTermXApp : Application() {
    private val log = LoggerFactory.getLogger(javaClass)
    private var edgeAgent: WindowsEdgeAgent? = null

    override fun start(stage: Stage) {
        val settings = SettingsStore.load()
        Strings.setLocale(settings.locale)
        val viewModel = AppViewModel()
        MainWindow(stage, viewModel, settings).show()
        runCatching { EdgeAgentConfig.fromEnvironment() }
            .onFailure { log.error("Configuración del agente inválida: {}", it.message) }
            .getOrNull()
            ?.let { config ->
                val processor = ApprovedRemoteTaskProcessor(JavaFxApprovalGate { stage })
                WindowsEdgeAgent(config, taskProcessor = processor).also { it.start(); edgeAgent = it }
            }
    }

    override fun stop() {
        edgeAgent?.close()
    }
}

fun main(args: Array<String>) {
    Application.launch(OpenTermXApp::class.java, *args)
}
