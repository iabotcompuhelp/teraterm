package com.opentermx.app.ui

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.AppSettings
import com.opentermx.app.settings.SettingsStore
import com.opentermx.app.ui.dialog.AdditionalSettingsDialog
import com.opentermx.app.ui.dialog.FontConfigDialog
import com.opentermx.app.ui.dialog.GeneralSettingsDialog
import com.opentermx.app.ui.dialog.KeyboardDialog
import com.opentermx.app.ui.dialog.KeybindingsDialog
import com.opentermx.app.ui.dialog.ProxyConfigDialog
import com.opentermx.app.ui.dialog.ScrollbackDialog
import com.opentermx.app.ui.dialog.SshAuthDialog
import com.opentermx.app.ui.dialog.SshGeneralDialog
import com.opentermx.app.ui.dialog.SshKeyGeneratorDialog
import com.opentermx.app.ui.dialog.TcpIpConfigDialog
import com.opentermx.app.ui.dialog.TerminalConfigDialog
import com.opentermx.app.ui.dialog.WindowConfigDialog
import com.opentermx.app.ui.terminal.TerminalView
import com.opentermx.common.connection.ConnectionConfig
import javafx.scene.control.Alert
import javafx.stage.FileChooser
import javafx.stage.Stage
import org.slf4j.LoggerFactory

/**
 * Acciones del menú Setup: cada `openXxx` muestra el dialog correspondiente, persiste
 * el resultado y propaga el cambio a los terminales vivos vía [TerminalSettingsApplier].
 * Extraído de `MainWindow` (split 2026-06). La mutación de settings pasa SIEMPRE por
 * el lambda [persist] para que `MainWindow` siga siendo la única fuente de verdad.
 */
class SetupDialogActions(
    private val stage: Stage,
    private val theme: Theme,
    private val settings: () -> AppSettings,
    private val persist: ((AppSettings) -> AppSettings) -> Unit,
    private val applier: TerminalSettingsApplier,
    private val setStatus: (String) -> Unit,
    private val rebuildMenusAndLabels: () -> Unit,
    private val currentSessionConfig: () -> ConnectionConfig?,
    private val openSavedSession: (com.opentermx.app.settings.SavedSession) -> Unit,
    private val allTerminals: () -> List<TerminalView>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun openFontDialog() {
        val s = settings()
        val dialog = FontConfigDialog(s.terminalFontFamily, s.terminalFontSize)
        val choice = dialog.showAndWait().orElse(null) ?: return
        persist { it.copy(terminalFontFamily = choice.family, terminalFontSize = choice.size) }
        applier.applyFont()
    }

    fun openKeybindingsDialog() {
        val updated = KeybindingsDialog(settings().accelerators).showAndWait().orElse(null) ?: return
        persist { it.copy(accelerators = updated) }
        rebuildMenusAndLabels()
    }

    fun openKeyboardConfig() {
        val updated = KeyboardDialog(settings().keyboard).showAndWait().orElse(null) ?: return
        persist { it.copy(keyboard = updated) }
        applier.applyKeyboardSettings(updated)
    }

    fun openScrollbackDialog() {
        val newLimit = ScrollbackDialog(settings().terminalScrollbackLimit).showAndWait().orElse(null) ?: return
        persist { it.copy(terminalScrollbackLimit = newLimit) }
        applier.applyScrollback(newLimit)
    }

    fun openTerminalConfig() {
        val updated = TerminalConfigDialog(settings().terminal).showAndWait().orElse(null) ?: return
        persist { it.copy(terminal = updated) }
        applier.applyTerminalSettings(updated)
    }

    fun openWindowConfig() {
        val previous = settings().window
        val tc = theme.terminalColors
        val updated = WindowConfigDialog(settings().window, tc.foreground, tc.background)
            .showAndWait().orElse(null) ?: return
        persist { it.copy(window = updated) }
        stage.opacity = updated.transparency
        stage.title = updated.titlePrefix.ifBlank { "COMPUHELP" }
        applier.applyMouseCursor(updated.mouseCursorMode)
        applier.applyTheme()
        if (updated.hideTitleBar != previous.hideTitleBar) {
            setStatus(Strings["status.windowDecorationRestart"])
        }
    }

    fun openProxyConfig() {
        val updated = ProxyConfigDialog(settings().proxy).showAndWait().orElse(null) ?: return
        persist { it.copy(proxy = updated) }
    }

    fun openSshGeneralConfig() {
        val updated = SshGeneralDialog(settings().sshGeneral).showAndWait().orElse(null) ?: return
        persist { it.copy(sshGeneral = updated) }
    }

    fun openSshAuthConfig() {
        val updated = SshAuthDialog(settings().sshAuth).showAndWait().orElse(null) ?: return
        persist { it.copy(sshAuth = updated) }
    }

    fun openSshKeyGenerator() {
        SshKeyGeneratorDialog(stage).show()
    }

    fun openTcpIpConfig() {
        val updated = TcpIpConfigDialog(settings().tcpIp).showAndWait().orElse(null) ?: return
        persist { it.copy(tcpIp = updated) }
    }

    fun openGeneralSettings() {
        val s = settings()
        val result = GeneralSettingsDialog(s.locale, s.general).showAndWait().orElse(null) ?: return
        val localeChanged = result.locale != s.locale
        persist { it.copy(locale = result.locale, general = result.general) }
        if (localeChanged) {
            Strings.setLocale(result.locale)
            rebuildMenusAndLabels()
        }
    }

    fun openAdditionalSettings() {
        val updated = AdditionalSettingsDialog(settings().additional).showAndWait().orElse(null) ?: return
        persist { it.copy(additional = updated) }
        applier.applyAdditionalSettings(updated)
    }

    /**
     * Abre el dialog de configuración del resaltado. Al confirmar, persiste el nuevo
     * `HighlightSettings` y re-instala el provider en cada terminal vivo (internamente
     * invalida el cache del engine y dispara `dirty=true`) para que los toggles se
     * vean sin esperar nueva data.
     */
    fun openHighlightConfigDialog() {
        val dialog = com.opentermx.app.ui.dialog.HighlightConfigDialog(settings().highlight)
        dialog.initOwner(stage)
        val updated = dialog.showAndWait().orElse(null) ?: return
        if (updated != settings().highlight) {
            persist { it.copy(highlight = updated) }
            allTerminals().forEach { t ->
                t.setHighlightSettingsProvider { settings().highlight }
            }
        }
    }

    fun saveSetup() {
        val file = FileChooser().apply {
            title = Strings["setup.saveSetup"]
            initialFileName = "opentermx-setup.json"
            extensionFilters.add(FileChooser.ExtensionFilter("JSON", "*.json"))
            initialDirectory = SettingsStore.configDir.toFile().takeIf { it.isDirectory }
        }.showSaveDialog(stage) ?: return
        val snapshot = com.opentermx.app.settings.SnapshotConverters.build(settings(), currentSessionConfig())
        runCatching { SettingsStore.exportSnapshot(snapshot, file) }
            .onSuccess {
                val msg = if (snapshot.savedSession != null)
                    Strings.format("status.setupSavedWithSession",
                        file.absolutePath, snapshot.savedSession.displayName)
                else
                    Strings.format("status.setupSaved", file.absolutePath)
                setStatus(msg)
            }
            .onFailure { setStatus(Strings.format("status.setupError", it.message ?: "")) }
    }

    fun restoreSetup() {
        val file = FileChooser().apply {
            title = Strings["setup.restoreSetup"]
            extensionFilters.add(FileChooser.ExtensionFilter("JSON", "*.json"))
            initialDirectory = SettingsStore.configDir.toFile().takeIf { it.isDirectory }
        }.showOpenDialog(stage) ?: return
        val snapshot = runCatching { SettingsStore.importSnapshot(file) }
            .onFailure {
                log.warn("Restore setup failed", it)
                setStatus(Strings.format("status.setupError", it.message ?: ""))
            }
            .getOrNull() ?: return

        val imported = snapshot.settings
        persist { imported }
        Strings.setLocale(imported.locale)
        stage.scene?.let { theme.applyTo(it) }
        applier.applyFont()
        applier.applyScrollback(imported.terminalScrollbackLimit)
        applier.applyTheme()
        applier.applyTerminalSettings(imported.terminal)
        applier.applyKeyboardSettings(imported.keyboard)
        applier.applyAdditionalSettings(imported.additional)
        stage.opacity = imported.window.transparency
        rebuildMenusAndLabels()
        setStatus(Strings.format("status.setupRestored", file.absolutePath))

        snapshot.savedSession?.let { saved ->
            val confirm = Alert(Alert.AlertType.CONFIRMATION).apply {
                title = Strings["setup.restoreSession.title"]
                headerText = Strings.format("setup.restoreSession.header", saved.displayName)
                contentText = Strings["setup.restoreSession.body"]
            }.showAndWait()
            if (confirm.isPresent && confirm.get() == javafx.scene.control.ButtonType.OK) {
                openSavedSession(saved)
            }
        }
    }

    fun showSetupDirectory() {
        val dir = SettingsStore.configDir.toFile()
        if (!dir.isDirectory) dir.mkdirs()
        runCatching { java.awt.Desktop.getDesktop().open(dir) }
            .onFailure { log.info("Desktop.open not available; just reporting path", it) }
        setStatus(Strings.format("status.setupDirOpen", dir.absolutePath))
    }

    fun loadKeyMap() {
        val file = FileChooser().apply {
            title = Strings["setup.loadKeymap"]
            extensionFilters.addAll(
                FileChooser.ExtensionFilter("Key map", "*.keymap", "*.properties"),
                FileChooser.ExtensionFilter("All files", "*.*"),
            )
        }.showOpenDialog(stage) ?: return
        runCatching {
            val props = java.util.Properties()
            file.bufferedReader(Charsets.UTF_8).use { props.load(it) }
            val merged = settings().accelerators.toMutableMap()
            var n = 0
            for (key in props.stringPropertyNames()) {
                val value = props.getProperty(key).trim()
                if (value.isEmpty()) merged.remove(key) else merged[key] = value
                n++
            }
            persist { it.copy(accelerators = merged) }
            rebuildMenusAndLabels()
            n
        }.onSuccess { count ->
            setStatus(Strings.format("status.keymapLoaded", count))
        }.onFailure {
            log.warn("Load key map failed", it)
            setStatus(Strings.format("status.setupError", it.message ?: ""))
        }
    }
}
