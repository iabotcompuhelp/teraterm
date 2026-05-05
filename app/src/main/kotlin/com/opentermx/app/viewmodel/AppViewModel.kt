package com.opentermx.app.viewmodel

import com.opentermx.common.session.Session
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList

class AppViewModel {
    val sessions: ObservableList<Session> = FXCollections.observableArrayList()
    val activeSession = SimpleObjectProperty<Session?>(null)

    fun addSession(session: Session) {
        sessions.add(session)
        activeSession.value = session
    }

    fun removeSession(session: Session) {
        sessions.remove(session)
        if (activeSession.value == session) activeSession.value = sessions.firstOrNull()
    }
}