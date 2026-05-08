package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.SettingsStore
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.TextArea
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import java.net.InetAddress
import java.time.ZoneId

/**
 * Help → Información del sistema. Read-only diagnostic block that the user can copy
 * verbatim into a bug report — no need to gather environment fields by hand.
 */
class SystemInfoDialog : Dialog<Void>() {

    init {
        title = Strings["help.systemInfo.title"]
        headerText = Strings["help.systemInfo.header"]

        val report = buildReport()
        val area = TextArea(report).apply {
            isEditable = false
            isWrapText = false
            prefRowCount = 16
            prefColumnCount = 56
            VBox.setVgrow(this, Priority.ALWAYS)
        }

        val copyBtn = Button(Strings["error.dialog.copy"]).apply {
            setOnAction {
                Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(report) })
                text = Strings["error.dialog.copied"]
            }
        }

        val toolbar = HBox(8.0, copyBtn).apply { padding = Insets(0.0, 0.0, 4.0, 0.0) }
        val content = VBox(8.0, toolbar, area).apply {
            padding = Insets(12.0)
            prefWidth = 560.0
            prefHeight = 420.0
        }
        dialogPane.content = content
        dialogPane.buttonTypes.setAll(ButtonType.CLOSE)
        setResultConverter { null }
    }

    private fun buildReport(): String {
        val p = System.getProperties()
        val rt = Runtime.getRuntime()
        val mb = 1024L * 1024L
        val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("?")
        return buildString {
            appendLine("== COMPUHELP / OpenTermX ==")
            appendLine("Build      : ${Strings["about.header"]}")
            appendLine()
            appendLine("OS         : ${p.getProperty("os.name")} ${p.getProperty("os.version")} (${p.getProperty("os.arch")})")
            appendLine("Java       : ${p.getProperty("java.version")} (${p.getProperty("java.vendor")})")
            appendLine("JavaFX     : ${runCatching { System.getProperty("javafx.runtime.version") }.getOrNull() ?: "?"}")
            appendLine("User home  : ${p.getProperty("user.home")}")
            appendLine("Working dir: ${p.getProperty("user.dir")}")
            appendLine("Hostname   : $host")
            appendLine("Locale     : ${java.util.Locale.getDefault()}")
            appendLine("Timezone   : ${ZoneId.systemDefault()}")
            appendLine("Settings   : ${SettingsStore.configDir}")
            appendLine()
            appendLine("Memory (MB)")
            appendLine("  max      : ${rt.maxMemory() / mb}")
            appendLine("  total    : ${rt.totalMemory() / mb}")
            appendLine("  free     : ${rt.freeMemory() / mb}")
            appendLine("  used     : ${(rt.totalMemory() - rt.freeMemory()) / mb}")
        }
    }
}