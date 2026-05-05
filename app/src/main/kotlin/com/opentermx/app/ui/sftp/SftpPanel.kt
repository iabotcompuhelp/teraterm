package com.opentermx.app.ui.sftp

import com.opentermx.app.i18n.Strings
import com.opentermx.ssh.SftpClient
import com.opentermx.ssh.SftpEntry
import com.opentermx.ssh.SshConnection
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.control.SplitPane
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.control.TextInputDialog
import javafx.scene.control.ToolBar
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.DateFormat
import java.util.Date
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Side-by-side local/remote file browser with upload/download/mkdir/rename/delete.
 * Owns the SftpClient — call [shutdown] when the host tab is closed.
 */
class SftpPanel(
    connection: SshConnection,
    initialLocalDir: Path = Paths.get(System.getProperty("user.home"))
) : BorderPane() {

    private val log = LoggerFactory.getLogger(javaClass)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var sftp: SftpClient? = null
    @Volatile private var inFlight: Job? = null

    private val localPathField = TextField(initialLocalDir.toAbsolutePath().toString())
    private val remotePathField = TextField("/")
    private val localTable = TableView<LocalEntry>()
    private val remoteTable = TableView<SftpEntry>()
    private val statusLabel = Label(Strings["sftp.connecting"])
    private val progressBar = ProgressBar(0.0).apply {
        prefWidth = 200.0
        isVisible = false
    }

    private val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    init {
        padding = Insets(8.0)
        configureLocalTable()
        configureRemoteTable()

        val localPane = buildLocalPane()
        val remotePane = buildRemotePane()
        val split = SplitPane(localPane, remotePane).apply {
            orientation = Orientation.HORIZONTAL
            setDividerPositions(0.5)
        }

        top = buildToolBar()
        center = split
        bottom = HBox(8.0, statusLabel, spacer(), progressBar).apply {
            padding = Insets(6.0, 0.0, 0.0, 0.0)
        }

        ioScope.launch {
            try {
                val client = connection.openSftp()
                sftp = client
                val pwd = client.pwd()
                Platform.runLater {
                    remotePathField.text = pwd
                    statusLabel.text = Strings["sftp.connected"]
                }
                refreshRemote()
            } catch (e: Exception) {
                log.warn("No se pudo abrir el canal SFTP", e)
                Platform.runLater {
                    statusLabel.text = Strings.format("sftp.openError", e.message ?: "")
                }
            }
        }

        refreshLocal()
    }

    fun shutdown() {
        inFlight?.cancel()
        runCatching { sftp?.close() }
        ioScope.cancel()
    }

    // ===== UI building =====

    private fun buildToolBar(): ToolBar {
        val refresh = Button(Strings["sftp.refresh"]).apply {
            setOnAction { refreshLocal(); refreshRemote() }
        }
        return ToolBar(refresh)
    }

    private fun buildLocalPane(): VBox {
        val up = Button(Strings["sftp.up"]).apply { setOnAction { localCdParent() } }
        localPathField.setOnAction { refreshLocal() }
        val nav = HBox(6.0, Label(Strings["sftp.local"]), localPathField, up).apply {
            padding = Insets(0.0, 0.0, 6.0, 0.0)
            HBox.setHgrow(localPathField, Priority.ALWAYS)
        }

        val upload = Button(Strings["sftp.upload"]).apply { setOnAction { uploadSelected() } }
        val mkdir = Button(Strings["sftp.mkdirLocal"]).apply { setOnAction { mkdirLocal() } }
        val delete = Button(Strings["sftp.delete"]).apply { setOnAction { deleteLocal() } }
        val actions = HBox(6.0, upload, mkdir, delete).apply {
            padding = Insets(6.0, 0.0, 0.0, 0.0)
        }

        return VBox(nav, localTable, actions).apply {
            VBox.setVgrow(localTable, Priority.ALWAYS)
        }
    }

    private fun buildRemotePane(): VBox {
        val up = Button(Strings["sftp.up"]).apply { setOnAction { remoteCdParent() } }
        remotePathField.setOnAction { refreshRemote() }
        val nav = HBox(6.0, Label(Strings["sftp.remote"]), remotePathField, up).apply {
            padding = Insets(0.0, 0.0, 6.0, 0.0)
            HBox.setHgrow(remotePathField, Priority.ALWAYS)
        }

        val download = Button(Strings["sftp.download"]).apply { setOnAction { downloadSelected() } }
        val mkdir = Button(Strings["sftp.mkdirRemote"]).apply { setOnAction { mkdirRemote() } }
        val rename = Button(Strings["sftp.rename"]).apply { setOnAction { renameRemote() } }
        val delete = Button(Strings["sftp.delete"]).apply { setOnAction { deleteRemote() } }
        val actions = HBox(6.0, download, mkdir, rename, delete).apply {
            padding = Insets(6.0, 0.0, 0.0, 0.0)
        }

        return VBox(nav, remoteTable, actions).apply {
            VBox.setVgrow(remoteTable, Priority.ALWAYS)
        }
    }

    private fun configureLocalTable() {
        val nameCol = TableColumn<LocalEntry, String>(Strings["sftp.col.name"]).apply {
            setCellValueFactory { SimpleStringProperty(displayName(it.value.name, it.value.directory)) }
            prefWidth = 240.0
        }
        val sizeCol = TableColumn<LocalEntry, String>(Strings["sftp.col.size"]).apply {
            setCellValueFactory {
                SimpleStringProperty(if (it.value.directory) "" else humanSize(it.value.size))
            }
            prefWidth = 90.0
        }
        val mtimeCol = TableColumn<LocalEntry, String>(Strings["sftp.col.modified"]).apply {
            setCellValueFactory { SimpleStringProperty(df.format(Date(it.value.modifiedMillis))) }
            prefWidth = 140.0
        }
        localTable.columns.setAll(nameCol, sizeCol, mtimeCol)
        localTable.placeholder = Label(Strings["sftp.empty"])
        localTable.setRowFactory {
            val row = javafx.scene.control.TableRow<LocalEntry>()
            row.setOnMouseClicked { ev ->
                if (ev.clickCount == 2 && !row.isEmpty) {
                    val item = row.item
                    if (item.directory) {
                        localPathField.text = item.path.toAbsolutePath().toString()
                        refreshLocal()
                    }
                }
            }
            row
        }
    }

    private fun configureRemoteTable() {
        val nameCol = TableColumn<SftpEntry, String>(Strings["sftp.col.name"]).apply {
            setCellValueFactory { SimpleStringProperty(displayName(it.value.name, it.value.directory)) }
            prefWidth = 240.0
        }
        val sizeCol = TableColumn<SftpEntry, String>(Strings["sftp.col.size"]).apply {
            setCellValueFactory {
                SimpleStringProperty(if (it.value.directory) "" else humanSize(it.value.size))
            }
            prefWidth = 90.0
        }
        val mtimeCol = TableColumn<SftpEntry, String>(Strings["sftp.col.modified"]).apply {
            setCellValueFactory { SimpleStringProperty(df.format(Date(it.value.modifiedMillis))) }
            prefWidth = 140.0
        }
        val permCol = TableColumn<SftpEntry, String>(Strings["sftp.col.permissions"]).apply {
            setCellValueFactory { ReadOnlyObjectWrapper(it.value.permissions) }
            prefWidth = 100.0
        }
        remoteTable.columns.setAll(nameCol, sizeCol, mtimeCol, permCol)
        remoteTable.placeholder = Label(Strings["sftp.empty"])
        remoteTable.setRowFactory {
            val row = javafx.scene.control.TableRow<SftpEntry>()
            row.setOnMouseClicked { ev ->
                if (ev.clickCount == 2 && !row.isEmpty) {
                    val item = row.item
                    if (item.directory) {
                        val newPath = if (item.isParent()) parentPath(remotePathField.text)
                                      else joinRemote(remotePathField.text, item.name)
                        remotePathField.text = newPath
                        refreshRemote()
                    }
                }
            }
            row
        }
    }

    // ===== Local operations =====

    private fun refreshLocal() {
        val dir = runCatching { Paths.get(localPathField.text) }.getOrNull()
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            localTable.items = FXCollections.observableArrayList()
            return
        }
        val items = mutableListOf<LocalEntry>()
        dir.parent?.let {
            items += LocalEntry(it, "..", true, 0L, 0L)
        }
        runCatching {
            Files.newDirectoryStream(dir).use { stream ->
                for (p in stream) {
                    runCatching {
                        val isDir = Files.isDirectory(p)
                        items += LocalEntry(
                            p, p.name, isDir,
                            if (isDir) 0L else Files.size(p),
                            Files.getLastModifiedTime(p).toMillis()
                        )
                    }
                }
            }
        }.onFailure { log.warn("listar local falló", it) }

        items.sortWith(compareByDescending<LocalEntry> { it.name == ".." }
            .thenByDescending { it.directory }
            .thenBy { it.name.lowercase() })
        localTable.items = FXCollections.observableArrayList(items)
    }

    private fun localCdParent() {
        val current = runCatching { Paths.get(localPathField.text) }.getOrNull() ?: return
        val parent = current.parent ?: return
        localPathField.text = parent.toAbsolutePath().toString()
        refreshLocal()
    }

    private fun mkdirLocal() {
        val name = promptString(Strings["sftp.mkdirLocal"], Strings["sftp.mkdirPrompt"]) ?: return
        if (name.isBlank()) return
        val parent = runCatching { Paths.get(localPathField.text) }.getOrNull() ?: return
        runCatching { Files.createDirectory(parent.resolve(name)) }
            .onFailure { showError(Strings.format("sftp.mkdirError", it.message ?: "")) }
        refreshLocal()
    }

    private fun deleteLocal() {
        val sel = localTable.selectionModel.selectedItem ?: return
        if (sel.name == "..") return
        if (!confirm(Strings.format("sftp.deleteConfirm", sel.name))) return
        runCatching {
            if (sel.directory) Files.delete(sel.path) else Files.delete(sel.path)
        }.onFailure { showError(Strings.format("sftp.deleteError", it.message ?: "")) }
        refreshLocal()
    }

    private fun uploadSelected() {
        val sel = localTable.selectionModel.selectedItem ?: return
        if (sel.directory) {
            showError(Strings["sftp.uploadDirNotSupported"])
            return
        }
        val client = sftp ?: return
        val remoteDir = remotePathField.text
        val targetPath = joinRemote(remoteDir, sel.name)
        beginTransfer(Strings.format("sftp.uploading", sel.name))
        inFlight = ioScope.launch {
            try {
                Files.newInputStream(sel.path).use { input ->
                    client.upload(input, targetPath, progressListener())
                }
                Platform.runLater {
                    statusLabel.text = Strings.format("sftp.uploaded", sel.name)
                    progressBar.isVisible = false
                }
                refreshRemote()
            } catch (e: Exception) {
                log.warn("upload falló", e)
                Platform.runLater {
                    statusLabel.text = Strings.format("sftp.transferError", e.message ?: "")
                    progressBar.isVisible = false
                }
            }
        }
    }

    // ===== Remote operations =====

    private fun refreshRemote() {
        val client = sftp ?: return
        val target = remotePathField.text.ifBlank { "/" }
        ioScope.launch {
            try {
                client.cd(target)
                val canonical = client.pwd()
                val listing = client.list(".")
                val sorted = listing.sortedWith(
                    compareByDescending<SftpEntry> { it.isParent() }
                        .thenByDescending { it.directory }
                        .thenBy { it.name.lowercase() }
                ).filterNot { it.isCurrent() }
                Platform.runLater {
                    remotePathField.text = canonical
                    remoteTable.items = FXCollections.observableArrayList(sorted)
                    statusLabel.text = Strings.format("sftp.listed", canonical)
                }
            } catch (e: Exception) {
                log.warn("list remoto falló", e)
                Platform.runLater {
                    statusLabel.text = Strings.format("sftp.listError", e.message ?: "")
                }
            }
        }
    }

    private fun remoteCdParent() {
        remotePathField.text = parentPath(remotePathField.text)
        refreshRemote()
    }

    private fun mkdirRemote() {
        val client = sftp ?: return
        val name = promptString(Strings["sftp.mkdirRemote"], Strings["sftp.mkdirPrompt"]) ?: return
        if (name.isBlank()) return
        val target = joinRemote(remotePathField.text, name)
        ioScope.launch {
            try {
                client.mkdir(target)
                refreshRemote()
            } catch (e: Exception) {
                log.warn("mkdir remoto falló", e)
                Platform.runLater {
                    showError(Strings.format("sftp.mkdirError", e.message ?: ""))
                }
            }
        }
    }

    private fun renameRemote() {
        val sel = remoteTable.selectionModel.selectedItem ?: return
        if (sel.isParent() || sel.isCurrent()) return
        val client = sftp ?: return
        val newName = promptString(Strings["sftp.rename"], Strings["sftp.renamePrompt"], sel.name) ?: return
        if (newName.isBlank() || newName == sel.name) return
        val from = joinRemote(remotePathField.text, sel.name)
        val to = joinRemote(remotePathField.text, newName)
        ioScope.launch {
            try {
                client.rename(from, to)
                refreshRemote()
            } catch (e: Exception) {
                log.warn("rename remoto falló", e)
                Platform.runLater {
                    showError(Strings.format("sftp.renameError", e.message ?: ""))
                }
            }
        }
    }

    private fun deleteRemote() {
        val sel = remoteTable.selectionModel.selectedItem ?: return
        if (sel.isParent() || sel.isCurrent()) return
        if (!confirm(Strings.format("sftp.deleteConfirm", sel.name))) return
        val client = sftp ?: return
        val target = joinRemote(remotePathField.text, sel.name)
        ioScope.launch {
            try {
                if (sel.directory) client.rmdir(target) else client.rm(target)
                refreshRemote()
            } catch (e: Exception) {
                log.warn("delete remoto falló", e)
                Platform.runLater {
                    showError(Strings.format("sftp.deleteError", e.message ?: ""))
                }
            }
        }
    }

    private fun downloadSelected() {
        val sel = remoteTable.selectionModel.selectedItem ?: return
        if (sel.directory) {
            showError(Strings["sftp.downloadDirNotSupported"])
            return
        }
        val client = sftp ?: return
        val localDir = runCatching { Paths.get(localPathField.text) }.getOrNull() ?: return
        val target = localDir.resolve(sel.name)
        val remotePath = joinRemote(remotePathField.text, sel.name)
        beginTransfer(Strings.format("sftp.downloading", sel.name))
        inFlight = ioScope.launch {
            try {
                Files.newOutputStream(target).use { out: OutputStream ->
                    client.download(remotePath, out, progressListener())
                }
                Platform.runLater {
                    statusLabel.text = Strings.format("sftp.downloaded", sel.name)
                    progressBar.isVisible = false
                }
                refreshLocal()
            } catch (e: Exception) {
                log.warn("download falló", e)
                Platform.runLater {
                    statusLabel.text = Strings.format("sftp.transferError", e.message ?: "")
                    progressBar.isVisible = false
                }
                runCatching { Files.deleteIfExists(target) }
            }
        }
    }

    // ===== Helpers =====

    private fun progressListener() = object : SftpClient.ProgressListener {
        override fun onProgress(transferred: Long, total: Long) {
            Platform.runLater {
                progressBar.progress = if (total > 0) transferred.toDouble() / total else -1.0
            }
        }
        override fun onComplete(success: Boolean) {}
    }

    private fun beginTransfer(message: String) {
        statusLabel.text = message
        progressBar.progress = 0.0
        progressBar.isVisible = true
    }

    private fun spacer(): Region = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }

    private fun parentPath(path: String): String {
        if (path.isEmpty() || path == "/") return "/"
        val trimmed = path.trimEnd('/')
        val cut = trimmed.lastIndexOf('/')
        return if (cut <= 0) "/" else trimmed.substring(0, cut)
    }

    private fun joinRemote(dir: String, name: String): String {
        if (name.startsWith("/")) return name
        val base = dir.trimEnd('/').ifEmpty { "" }
        return "$base/$name"
    }

    private fun displayName(name: String, isDir: Boolean): String =
        if (isDir && name != "..") "$name/" else name

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble() / 1024.0
        var i = 0
        while (v >= 1024.0 && i < units.lastIndex) { v /= 1024.0; i++ }
        return "%.1f %s".format(v, units[i])
    }

    private fun promptString(title: String, header: String, initial: String = ""): String? {
        val dlg = TextInputDialog(initial)
        dlg.title = title
        dlg.headerText = header
        return dlg.showAndWait().orElse(null)
    }

    private fun confirm(message: String): Boolean {
        val alert = Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL)
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK
    }

    private fun showError(message: String) {
        Alert(Alert.AlertType.ERROR, message, ButtonType.OK).showAndWait()
    }

    private data class LocalEntry(
        val path: Path,
        val name: String,
        val directory: Boolean,
        val size: Long,
        val modifiedMillis: Long
    )
}