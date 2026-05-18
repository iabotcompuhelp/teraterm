package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.SettingsStore
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.stage.Window
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Reusable informational/error dialog. Adds three things on top of plain Alert:
 *
 *  - "Detalles" expandable area with the full technical message and an optional stack trace,
 *  - "Copiar" button that puts a diagnostic block on the clipboard,
 *  - "Abrir carpeta de logs" button (only when severity is ERROR or WARNING).
 *
 * Use the static helpers [error], [warning] and [info] from any thread; the dialog itself is
 * always shown on the JavaFX application thread.
 */
object ErrorDialog {

    enum class Severity { INFO, WARNING, ERROR }

    fun error(
        owner: Window? = null,
        title: String? = null,
        header: String? = null,
        message: String? = null,
        cause: Throwable? = null,
        details: String? = null,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        friendlyTip: String? = null,
    ) {
        show(Severity.ERROR, owner, title, header, message, cause, details, actionLabel, onAction, friendlyTip)
    }

    fun warning(
        owner: Window? = null,
        title: String? = null,
        header: String? = null,
        message: String? = null,
        cause: Throwable? = null,
        details: String? = null,
    ) {
        show(Severity.WARNING, owner, title, header, message, cause, details, null, null, null)
    }

    fun info(
        owner: Window? = null,
        title: String? = null,
        header: String? = null,
        message: String? = null,
        details: String? = null,
    ) {
        show(Severity.INFO, owner, title, header, message, null, details, null, null, null)
    }

    private fun show(
        severity: Severity,
        owner: Window?,
        title: String?,
        header: String?,
        message: String?,
        cause: Throwable?,
        details: String?,
        actionLabel: String?,
        onAction: (() -> Unit)?,
        friendlyTip: String?,
    ) {
        if (Platform.isFxApplicationThread()) {
            buildAndShow(severity, owner, title, header, message, cause, details, actionLabel, onAction, friendlyTip)
        } else {
            Platform.runLater {
                buildAndShow(severity, owner, title, header, message, cause, details, actionLabel, onAction, friendlyTip)
            }
        }
    }

    private fun buildAndShow(
        severity: Severity,
        owner: Window?,
        title: String?,
        header: String?,
        message: String?,
        cause: Throwable?,
        details: String?,
        actionLabel: String?,
        onAction: (() -> Unit)?,
        friendlyTip: String?,
    ) {
        val type = when (severity) {
            Severity.INFO -> Alert.AlertType.INFORMATION
            Severity.WARNING -> Alert.AlertType.WARNING
            Severity.ERROR -> Alert.AlertType.ERROR
        }

        val resolvedMessage = message?.takeIf { it.isNotBlank() }
            ?: cause?.message
            ?: cause?.javaClass?.simpleName
            ?: ""
        val resolvedTitle = title ?: when (severity) {
            Severity.INFO -> Strings["error.dialog.title.info"]
            Severity.WARNING -> Strings["error.dialog.title.warning"]
            Severity.ERROR -> Strings["error.dialog.title.error"]
        }
        val resolvedHeader = header ?: when (severity) {
            Severity.INFO -> Strings["error.dialog.header.info"]
            Severity.WARNING -> Strings["error.dialog.header.warning"]
            Severity.ERROR -> Strings["error.dialog.header.error"]
        }

        val alert = Alert(type).apply {
            this.title = resolvedTitle
            this.headerText = resolvedHeader
            this.contentText = resolvedMessage
            owner?.let { initOwner(it) }
            // Make sure the expand region grows with the dialog when "Detalles" is open.
            dialogPane.minWidth = 460.0
        }

        val technical = buildTechnicalReport(severity, resolvedTitle, resolvedHeader, resolvedMessage, cause, details)

        if (technical.isNotBlank()) {
            val area = TextArea(technical).apply {
                isEditable = false
                isWrapText = false
                styleClass += "error-details"
                maxWidth = Double.MAX_VALUE
                maxHeight = Double.MAX_VALUE
            }
            GridPane.setVgrow(area, Priority.ALWAYS)
            GridPane.setHgrow(area, Priority.ALWAYS)
            val expandable = GridPane().apply {
                maxWidth = Double.MAX_VALUE
                add(Label(Strings["error.dialog.details.label"]), 0, 0)
                add(area, 0, 1)
            }
            alert.dialogPane.expandableContent = expandable
        }

        // Buttons row at the bottom: Copiar (always) + Abrir carpeta de logs (errors/warnings).
        val copyBtn = Button(Strings["error.dialog.copy"]).apply {
            setOnAction {
                val payload = if (technical.isNotBlank()) technical else "${resolvedHeader}\n${resolvedMessage}"
                Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(payload) })
                text = Strings["error.dialog.copied"]
            }
        }
        val buttons = HBox(8.0, copyBtn).apply {
            padding = Insets(6.0, 0.0, 0.0, 0.0)
        }
        // Action contextual (Phase 2.5 T4): cuando el caller detecta un error específico
        // — KEX/cipher/MAC fail — pasa label + lambda para que el usuario salte directo
        // a Setup → SSH General sin tener que navegar el menú con un dialog modal abierto.
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            val actionBtn = Button(actionLabel).apply {
                styleClass += "default-button"
                setOnAction {
                    val window = (scene?.window as? javafx.stage.Stage)
                    window?.close()
                    onAction.invoke()
                }
            }
            buttons.children.add(0, actionBtn)
        }
        if (severity != Severity.INFO) {
            val openLogsBtn = Button(Strings["error.dialog.openLogs"]).apply {
                setOnAction { openLogsDirectory() }
            }
            buttons.children += openLogsBtn
        }

        // Place the buttons inside the content of the alert (above the OK button).
        // Si hay friendlyTip lo mostramos PRIMERO (mensaje claro accionable); el `resolvedMessage`
        // técnico baja al área de detalles colapsable a través del `technical` report.
        val contentChildren = mutableListOf<javafx.scene.Node>()
        if (!friendlyTip.isNullOrBlank()) {
            contentChildren += Label(friendlyTip).apply {
                isWrapText = true
                maxWidth = 560.0
                styleClass += "error-friendly-tip"
            }
        }
        contentChildren += Label(resolvedMessage).apply { isWrapText = true; maxWidth = 560.0 }
        contentChildren += buttons
        val content = VBox(8.0, *contentChildren.toTypedArray()).apply {
            padding = Insets(4.0, 0.0, 0.0, 0.0)
        }
        alert.dialogPane.content = content
        alert.dialogPane.buttonTypes.setAll(ButtonType.CLOSE)
        alert.showAndWait()
    }

    private fun buildTechnicalReport(
        severity: Severity,
        title: String,
        header: String,
        message: String,
        cause: Throwable?,
        details: String?,
    ): String {
        val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val builder = StringBuilder()
        builder.appendLine("[$now] ${severity.name}")
        builder.appendLine("Title : $title")
        builder.appendLine("Header: $header")
        if (message.isNotBlank()) builder.appendLine("Message: $message")
        if (!details.isNullOrBlank()) {
            builder.appendLine("---- Details ----")
            builder.appendLine(details.trim())
        }
        if (cause != null) {
            builder.appendLine("---- Stack trace ----")
            val sw = StringWriter()
            cause.printStackTrace(PrintWriter(sw))
            builder.append(sw.toString())
        }
        builder.appendLine("---- Environment ----")
        builder.appendLine("OS    : ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
        builder.appendLine("Java  : ${System.getProperty("java.version")}")
        builder.appendLine("Locale: ${java.util.Locale.getDefault()}")
        return builder.toString()
    }

    private fun openLogsDirectory() {
        val dir = SettingsStore.configDir.toFile()
        if (!dir.isDirectory) dir.mkdirs()
        runCatching { java.awt.Desktop.getDesktop().open(dir) }
    }
}