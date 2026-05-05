package com.opentermx.app.ui

import com.opentermx.app.viewmodel.AppViewModel
import com.opentermx.common.session.Session
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.layout.BorderPane
import javafx.util.Callback

class SessionListView(viewModel: AppViewModel) : BorderPane() {

    private val list = ListView(viewModel.sessions).apply {
        styleClass += "session-list"
        cellFactory = Callback {
            object : ListCell<Session>() {
                override fun updateItem(item: Session?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null
                    else "${item.name}  (${item.config.displayName})"
                }
            }
        }
    }

    init {
        styleClass += "session-list-pane"
        top = Label("Sesiones activas").apply { styleClass += "panel-header" }
        center = list
    }
}