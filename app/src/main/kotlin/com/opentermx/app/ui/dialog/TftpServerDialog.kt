package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.tftp.server.TftpServer
import com.opentermx.tftp.server.TftpServerConfig
import com.opentermx.tftp.server.TftpServerEvent
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.DirectoryChooser
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.Window
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import java.time.ZoneId

class TftpServerDialog(
    owner: Window,
    defaultPort: Int = 69,
    defaultRoot: String = System.getProperty("user.home"),
) : Stage() {

    private val log = LoggerFactory.getLogger(javaClass)

    private val portSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 65535, defaultPort, 1)
        isEditable = true
    }
    private val rootField = TextField(defaultRoot)
    private val browseButton = Button(Strings["tftp.browse"]).apply {
        setOnAction { browseRoot() }
    }
    private val allowGetCheck = CheckBox(Strings["tftp.allowGet"]).apply { isSelected = true }
    private val allowPutCheck = CheckBox(Strings["tftp.allowPut"]).apply { isSelected = false }
    private val statusLabel = Label(Strings["tftp.serverStopped"])
    private val startButton = Button(Strings["tftp.start"])
    private val stopButton = Button(Strings["tftp.stop"]).apply { isDisable = true }
    private val closeButton = Button(Strings["tftp.close"]).apply { setOnAction { onClose() } }
    private val logArea = TextArea().apply {
        isEditable = false
        prefRowCount = 12
    }

    @Volatile private var server: TftpServer? = null
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    init {
        title = Strings["tftp.serverTitle"]
        initOwner(owner)
        initModality(Modality.NONE)

        val grid = GridPane().apply {
            hgap = 8.0; vgap = 8.0
            padding = Insets(16.0, 16.0, 8.0, 16.0)
            add(Label(Strings["tftp.port"]), 0, 0); add(portSpinner, 1, 0)
            add(Label(Strings["tftp.rootDir"]), 0, 1); add(rootField, 1, 1, 2, 1); add(browseButton, 3, 1)
            add(allowGetCheck, 1, 2); add(allowPutCheck, 2, 2)
        }
        rootField.maxWidth = Double.MAX_VALUE
        GridPane.setHgrow(rootField, Priority.ALWAYS)

        startButton.setOnAction { startServer() }
        stopButton.setOnAction { stopServer() }

        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        val buttons = HBox(8.0, spacer, startButton, stopButton, closeButton)

        val layout = VBox(10.0, grid, statusLabel, logArea, buttons).apply {
            padding = Insets(0.0, 16.0, 16.0, 16.0)
            VBox.setVgrow(logArea, Priority.ALWAYS)
            minWidth = 560.0
            minHeight = 420.0
        }
        scene = Scene(layout)
        setOnCloseRequest { e ->
            if (server?.isRunning == true) { onClose(); e.consume() }
        }
    }

    private fun browseRoot() {
        val dir = DirectoryChooser().apply {
            title = Strings["tftp.rootDir"]
            initialDirectory = java.io.File(rootField.text.ifBlank { System.getProperty("user.home") })
        }.showDialog(this) ?: return
        rootField.text = dir.absolutePath
    }

    private fun startServer() {
        if (server?.isRunning == true) return
        val rootPath = Path.of(rootField.text.ifBlank { "." })
        if (!java.nio.file.Files.isDirectory(rootPath)) {
            statusLabel.text = Strings.format("tftp.errRoot", rootPath)
            return
        }
        val config = TftpServerConfig(
            portSpinner.value,
            rootPath,
            allowGetCheck.isSelected,
            allowPutCheck.isSelected,
            5,
            5,
        )
        val srv = TftpServer(config)
        srv.addListener { event -> Platform.runLater { onEvent(event) } }
        try {
            srv.start()
            server = srv
            startButton.isDisable = true
            stopButton.isDisable = false
            portSpinner.isDisable = true
            rootField.isDisable = true
            browseButton.isDisable = true
            allowGetCheck.isDisable = true
            allowPutCheck.isDisable = true
            statusLabel.text = Strings.format("tftp.serverRunning", srv.actualPort())
        } catch (ex: Exception) {
            log.warn("TFTP server start failed", ex)
            statusLabel.text = Strings.format("tftp.errStart", ex.message ?: ex.javaClass.simpleName)
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        startButton.isDisable = false
        stopButton.isDisable = true
        portSpinner.isDisable = false
        rootField.isDisable = false
        browseButton.isDisable = false
        allowGetCheck.isDisable = false
        allowPutCheck.isDisable = false
        statusLabel.text = Strings["tftp.serverStopped"]
    }

    private fun onClose() {
        server?.stop()
        server = null
        close()
    }

    private fun onEvent(event: TftpServerEvent) {
        val ts = timeFormatter.format(event.timestamp())
        val peer = event.peer()?.toString() ?: ""
        val file = event.file() ?: ""
        val line = "[$ts] ${event.kind()} ${peer.ifBlank { "" }} ${file.ifBlank { "" }}\t${event.message()}"
        logArea.appendText(line + "\n")
    }
}