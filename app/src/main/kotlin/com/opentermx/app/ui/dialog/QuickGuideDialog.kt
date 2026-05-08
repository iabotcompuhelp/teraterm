package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.layout.VBox

/**
 * Quick reference shown from Help → Guía rápida. Each tab covers a single topic that a
 * first-time user needs in order to get something on screen. Strings live in messages.properties
 * (namespace `help.guide.*`) so translations are kept consistent with the rest of the UI.
 */
class QuickGuideDialog : Dialog<Void>() {

    init {
        title = Strings["help.guide.title"]
        headerText = Strings["help.guide.header"]

        val tabs = TabPane().apply {
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
            tabs += topicTab("help.guide.firstSteps")
            tabs += topicTab("help.guide.ssh")
            tabs += topicTab("help.guide.telnet")
            tabs += topicTab("help.guide.serial")
            tabs += topicTab("help.guide.tftp")
            tabs += topicTab("help.guide.macros")
            tabs += topicTab("help.guide.transfer")
            tabs += topicTab("help.guide.shortcuts")
        }

        dialogPane.content = tabs.also {
            it.prefWidth = 640.0
            it.prefHeight = 460.0
        }
        dialogPane.buttonTypes.setAll(ButtonType.CLOSE)
        setResultConverter { null }
    }

    private fun topicTab(keyPrefix: String): Tab {
        val titleKey = "${keyPrefix}.title"
        val bodyKey = "${keyPrefix}.body"
        val title = Strings[titleKey]
        val body = Strings[bodyKey]
        val text = Label(body).apply {
            isWrapText = true
            maxWidth = 580.0
            padding = Insets(0.0, 0.0, 8.0, 0.0)
        }
        val box = VBox(8.0, text).apply { padding = Insets(14.0) }
        val scroll = ScrollPane(box).apply {
            isFitToWidth = true
            isFitToHeight = false
        }
        return Tab(title, scroll)
    }
}