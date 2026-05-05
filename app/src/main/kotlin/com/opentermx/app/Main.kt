package com.opentermx.app

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.SettingsStore
import com.opentermx.app.ui.MainWindow
import com.opentermx.app.viewmodel.AppViewModel
import javafx.application.Application
import javafx.stage.Stage

class OpenTermXApp : Application() {
    override fun start(stage: Stage) {
        val settings = SettingsStore.load()
        Strings.setLocale(settings.locale)
        val viewModel = AppViewModel()
        MainWindow(stage, viewModel, settings).show()
    }
}

fun main(args: Array<String>) {
    Application.launch(OpenTermXApp::class.java, *args)
}