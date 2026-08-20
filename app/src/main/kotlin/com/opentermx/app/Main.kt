package com.opentermx.app

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.SettingsStore
import com.opentermx.app.ui.MainWindow
import com.opentermx.app.viewmodel.AppViewModel
import javafx.application.Application
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import com.opentermx.app.ui.ai.ApprovedRemoteTaskProcessor
import com.opentermx.app.ui.ai.JavaFxApprovalGate
import com.opentermx.app.agent.EdgeAgentManager

class OpenTermXApp : Application() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun start(stage: Stage) {
        var settings = SettingsStore.load()
        val migratedAgent = settings.edgeAgent.migrateLegacyToken()
        if (migratedAgent != settings.edgeAgent) {
            settings = settings.copy(edgeAgent = migratedAgent)
            SettingsStore.save(settings)
        }
        Strings.setLocale(settings.locale)
        val viewModel = AppViewModel()
        MainWindow(stage, viewModel, settings).show()
        runCatching { settings.edgeAgent.runtimeConfig() }
            .onFailure { log.error("Configuración del agente inválida: {}", it.message) }
            .getOrNull()
            .let { config ->
                val processor = config?.let { ApprovedRemoteTaskProcessor(JavaFxApprovalGate { stage }) }
                EdgeAgentManager.configure(config, processor)
                if (config != null) EdgeAgentManager.start()
            }
    }

    override fun stop() {
        EdgeAgentManager.stop()
    }
}

fun main(args: Array<String>) {
    Application.launch(OpenTermXApp::class.java, *args)
}
