package com.opentermx.app.ui.dialog

import com.opentermx.ai.ApiKeyValidator
import com.opentermx.ai.ProviderRegistry
import com.opentermx.ai.rag.DocumentLoaders
import com.opentermx.ai.rag.IndexedFile
import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.AiAssistantSettings
import com.opentermx.app.ui.ai.KnowledgeBaseHolder
import com.opentermx.common.ai.ConnectionResult
import com.opentermx.common.ai.LlmError
import com.opentermx.common.ai.ProviderKind
import com.opentermx.common.crypto.EncryptedValue
import com.opentermx.common.crypto.SecretCipher
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.PasswordField
import javafx.scene.control.ScrollPane
import javafx.scene.control.Slider
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TextArea
import javafx.scene.control.TitledPane
import javafx.scene.control.Tooltip
import javafx.scene.input.Clipboard
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * Setup → AI Assistant…  (spec v4 § Grupo 5).
 *
 * En Fase 1 cubre 3 providers cloud (Claude/ChatGPT/Gemini). Local LLMs (Ollama / LM Studio)
 * y la Knowledge Base (RAG) aterrizan en fases 2 y 4 respectivamente, con UI placeholder.
 */
class AiAssistantDialog(initial: AiAssistantSettings) : Dialog<AiAssistantSettings>() {

    private val providerCombo = ComboBox<ProviderKind>().apply {
        items.addAll(*ProviderKind.entries.toTypedArray())
        value = initial.providerKind()
    }
    private val apiKeyField = PasswordField().apply {
        promptText = Strings["setup.ai.apiKeyPrompt"]
        prefColumnCount = 36
    }
    private val pasteBtn = Button(Strings["setup.ai.paste"]).apply {
        tooltip = Tooltip(Strings["setup.ai.pasteTooltip"])
        setOnAction {
            val clip = Clipboard.getSystemClipboard()
            if (clip.hasString()) apiKeyField.text = clip.string.trim()
        }
    }
    private val getKeyBtn = Button(Strings["setup.ai.getKey"]).apply {
        setOnAction { openBrowser(ProviderRegistry.consoleUrlFor(providerCombo.value)) }
    }
    private val setupGuide = TitledPane().apply {
        text = Strings["setup.ai.setupGuide"]
        isExpanded = false
        content = Label("").apply { isWrapText = true; prefWidth = 460.0 }
    }
    private val modelCombo = ComboBox<String>().apply { isEditable = true; prefWidth = 240.0 }
    private val refreshModelsBtn = Button(Strings["setup.ai.refreshModels"]).apply {
        tooltip = Tooltip(Strings["setup.ai.refreshModelsTooltip"])
        isVisible = false; isManaged = false
        setOnAction { runRefreshModels() }
    }
    private val localEndpointField = javafx.scene.control.TextField().apply { prefColumnCount = 28; isDisable = true }

    private val testBtn = Button(Strings["setup.ai.testConnection"])
    private val testStatus = Label("").apply { isWrapText = true; maxWidth = 480.0 }

    private val temperatureSlider = Slider(0.0, 1.0, initial.temperature).apply {
        majorTickUnit = 0.2; isShowTickMarks = true; isShowTickLabels = true; prefWidth = 200.0
    }
    private val temperatureValue = Label(String.format("%.2f", initial.temperature))
    private val maxTokensSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(256, 32_768, initial.maxTokens, 256)
        isEditable = true
        prefWidth = 120.0
    }
    private val includeContextCheck = CheckBox(Strings["setup.ai.includeContext"])
        .apply { isSelected = initial.includeTerminalContext }
    private val detectVendorCheck = CheckBox(Strings["setup.ai.detectVendor"])
        .apply { isSelected = initial.detectVendor }

    private val privacyLabel = Label().apply { isWrapText = true; maxWidth = 480.0; style = "-fx-text-fill: -fx-accent;" }

    private val systemPromptArea = TextArea(initial.systemPrompt).apply {
        isWrapText = true; prefRowCount = 18; prefColumnCount = 64
    }
    private val restorePromptBtn = Button(Strings["setup.ai.restorePrompt"]).apply {
        setOnAction { systemPromptArea.text = AiAssistantSettings.DEFAULT_SYSTEM_PROMPT }
    }

    private val statusBadge = Label("").apply { padding = Insets(4.0, 10.0, 4.0, 10.0) }

    // -------- Knowledge Base (Fase 4) --------
    private val kbFiles = FXCollections.observableArrayList<IndexedFile>()
    private val kbListView = ListView(kbFiles).apply {
        prefHeight = 220.0
        cellFactory = javafx.util.Callback {
            object : ListCell<IndexedFile>() {
                override fun updateItem(item: IndexedFile?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (empty || item == null) {
                        text = null; graphic = null
                        return
                    }
                    val name = Path.of(item.path).fileName?.toString() ?: item.path
                    val sizeKb = item.sizeBytes / 1024
                    val status = when {
                        item.error != null -> "❌ ${item.error}"
                        item.chunkCount < 0 -> Strings["setup.ai.kb.pending"]
                        else -> Strings.format("setup.ai.kb.chunkCount", item.chunkCount)
                    }
                    text = "$name  •  ${sizeKb}KB  •  $status"
                    style = if (item.error != null) "-fx-text-fill: #c62828;" else ""
                }
            }
        }
    }
    private val kbStatusLabel = Label("").apply {
        style = "-fx-text-fill: derive(-fx-text-base-color, 30%);"
    }
    private val kbChunkSizeSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(50, 4000, initial.ragChunkSize, 50)
        isEditable = true; prefWidth = 100.0
    }
    private val kbOverlapSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(0, 1000, initial.ragChunkOverlap, 10)
        isEditable = true; prefWidth = 100.0
    }
    private val kbTopKSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 20, initial.ragTopK, 1)
        isEditable = true; prefWidth = 80.0
    }
    private val kbAddBtn = Button(Strings["setup.ai.kb.add"]).apply { setOnAction { onAddKnowledgeFile() } }
    private val kbRemoveBtn = Button(Strings["setup.ai.kb.remove"]).apply {
        setOnAction { onRemoveKnowledgeFile() }
    }
    private val kbReindexBtn = Button(Strings["setup.ai.kb.reindex"]).apply {
        setOnAction { onReindexAll() }
    }

    // Mutable working state — los API keys ya configurados se mantienen aquí hasta OK.
    private val apiKeys: MutableMap<String, EncryptedValue> = initial.apiKeys.toMutableMap()
    private val localEndpoints: MutableMap<String, String> = initial.localEndpoints.toMutableMap()
    private val selectedModels: MutableMap<String, String> = initial.selectedModels.toMutableMap()
    private var lastVerifiedAt: Long? = initial.lastVerifiedAt
    private var lastVerifiedProvider: String? = initial.lastVerifiedProvider
    private var lastVerifiedModel: String? = initial.lastVerifiedModel

    private val keyFormatHint = Label("").apply { style = "-fx-text-fill: derive(-fx-text-base-color, 30%); -fx-font-size: 10.5px;" }

    init {
        title = Strings["setup.ai.title"]
        headerText = Strings["setup.ai.header"]
        isResizable = true

        temperatureSlider.valueProperty().addListener { _, _, v ->
            temperatureValue.text = String.format("%.2f", v.toDouble())
        }
        providerCombo.valueProperty().addListener { _, _, v -> onProviderChanged(v) }

        testBtn.setOnAction { runTestConnection() }

        val provider = providerCombo.value
        onProviderChanged(provider) // siembra UI

        val tabs = TabPane().apply {
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
            tabs += Tab(Strings["setup.ai.tabProvider"], buildProviderTab())
            tabs += Tab(Strings["setup.ai.tabSystemPrompt"], buildSystemPromptTab())
            tabs += Tab(Strings["setup.ai.tabKnowledge"], buildKnowledgeBaseTab())
        }

        dialogPane.content = VBox(8.0, tabs, statusBadge).apply { padding = Insets(8.0) }
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        refreshStatusBadge()

        setResultConverter { btn ->
            if (btn != ButtonType.OK) return@setResultConverter null
            stashCurrentApiKey()
            AiAssistantSettings(
                provider = providerCombo.value.name,
                apiKeys = apiKeys.toMap(),
                localEndpoints = localEndpoints.toMap(),
                selectedModels = selectedModels.toMap(),
                temperature = temperatureSlider.value,
                maxTokens = maxTokensSpinner.value,
                includeTerminalContext = includeContextCheck.isSelected,
                detectVendor = detectVendorCheck.isSelected,
                systemPrompt = systemPromptArea.text,
                knowledgeBaseFiles = kbFiles.map { it.path },
                ragChunkSize = kbChunkSizeSpinner.value,
                ragChunkOverlap = kbOverlapSpinner.value,
                ragTopK = kbTopKSpinner.value,
                lastVerifiedAt = lastVerifiedAt,
                lastVerifiedProvider = lastVerifiedProvider,
                lastVerifiedModel = lastVerifiedModel,
            )
        }
    }

    // ---------- Tabs ----------

    private fun buildProviderTab(): javafx.scene.Node {
        val grid = GridPane().apply {
            hgap = 10.0; vgap = 8.0; padding = Insets(16.0)
            var r = 0
            add(Label(Strings["setup.ai.provider"]), 0, r); add(providerCombo, 1, r); r++
            add(Label(Strings["setup.ai.apiKey"]), 0, r)
            add(HBox(6.0, apiKeyField, pasteBtn, getKeyBtn).apply { alignment = Pos.CENTER_LEFT }, 1, r); r++
            add(Label(""), 0, r); add(keyFormatHint, 1, r); r++
            add(Label(""), 0, r); add(setupGuide, 1, r); r++
            add(Label(Strings["setup.ai.localEndpoint"]), 0, r); add(localEndpointField, 1, r); r++
            add(Label(Strings["setup.ai.model"]), 0, r)
            add(HBox(6.0, modelCombo, refreshModelsBtn).apply { alignment = Pos.CENTER_LEFT }, 1, r); r++
            add(Label(Strings["setup.ai.temperature"]), 0, r)
            add(HBox(8.0, temperatureSlider, temperatureValue).apply { alignment = Pos.CENTER_LEFT }, 1, r); r++
            add(Label(Strings["setup.ai.maxTokens"]), 0, r); add(maxTokensSpinner, 1, r); r++
            add(Label(""), 0, r); add(includeContextCheck, 1, r); r++
            add(Label(""), 0, r); add(detectVendorCheck, 1, r); r++
            add(Label(""), 0, r); add(privacyLabel, 1, r); r++
            add(Label(""), 0, r); add(HBox(8.0, testBtn).apply { alignment = Pos.CENTER_LEFT }, 1, r); r++
            add(Label(""), 0, r); add(testStatus, 1, r); r++
        }
        return ScrollPane(grid).apply { isFitToWidth = true }
    }

    private fun buildSystemPromptTab(): javafx.scene.Node = VBox(8.0).apply {
        padding = Insets(16.0)
        children += Label(Strings["setup.ai.systemPromptHelp"]).apply { isWrapText = true }
        children += systemPromptArea.also { VBox.setVgrow(it, Priority.ALWAYS) }
        children += HBox(8.0, restorePromptBtn).apply { alignment = Pos.CENTER_RIGHT }
        children += Label(Strings["setup.ai.systemPromptVariables"]).apply { isWrapText = true }
    }

    private fun buildKnowledgeBaseTab(): javafx.scene.Node {
        hydrateKnowledgeBaseUI()
        val header = Label(Strings["setup.ai.kb.header"]).apply { isWrapText = true; maxWidth = 560.0 }
        val hint = Label(Strings.format("setup.ai.kb.formatsHint", DocumentLoaders.SUPPORTED_EXTENSIONS.joinToString(", "))).apply {
            isWrapText = true; style = "-fx-text-fill: derive(-fx-text-base-color, 30%); -fx-font-size: 11px;"
        }
        val tuningRow = HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            children += Label(Strings["setup.ai.kb.chunkSize"]); children += kbChunkSizeSpinner
            children += Label(Strings["setup.ai.kb.chunkOverlap"]); children += kbOverlapSpinner
            children += Label(Strings["setup.ai.kb.topK"]); children += kbTopKSpinner
        }
        val buttonRow = HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            children += kbAddBtn
            children += kbRemoveBtn
            children += kbReindexBtn
            children += Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
            children += kbStatusLabel
        }
        return VBox(10.0).apply {
            padding = Insets(16.0)
            children += header
            children += hint
            children += kbListView.also { VBox.setVgrow(it, Priority.ALWAYS) }
            children += tuningRow
            children += buttonRow
        }
    }

    /** Carga la lista persistida desde settings y muestra un estado coherente al abrir el diálogo. */
    private fun hydrateKnowledgeBaseUI() {
        kbFiles.setAll(currentKnowledgeBase().summary().files)
        refreshKnowledgeBaseStatus()
    }

    private fun currentKnowledgeBase() = KnowledgeBaseHolder.get(snapshotSettingsForKb())

    private fun snapshotSettingsForKb(): AiAssistantSettings = AiAssistantSettings(
        knowledgeBaseFiles = kbFiles.map { it.path },
        ragChunkSize = kbChunkSizeSpinner.value,
        ragChunkOverlap = kbOverlapSpinner.value,
        ragTopK = kbTopKSpinner.value,
    )

    private fun refreshKnowledgeBaseStatus() {
        val s = currentKnowledgeBase().summary()
        kbStatusLabel.text = if (s.totalDocuments == 0) {
            Strings["setup.ai.kb.empty"]
        } else {
            Strings.format("setup.ai.kb.summary", s.totalDocuments, s.totalChunks)
        }
    }

    private fun onAddKnowledgeFile() {
        val extensions = DocumentLoaders.SUPPORTED_EXTENSIONS.map { "*.$it" }.toTypedArray()
        val chosen = FileChooser().apply {
            title = Strings["setup.ai.kb.addTitle"]
            extensionFilters.add(FileChooser.ExtensionFilter("Knowledge", *extensions))
            extensionFilters.add(FileChooser.ExtensionFilter("All files", "*.*"))
        }.showOpenDialog(dialogPane.scene?.window) ?: return
        kbStatusLabel.text = Strings.format("setup.ai.kb.indexing", chosen.name)
        kbAddBtn.isDisable = true
        thread(start = true, isDaemon = true, name = "ai-kb-index") {
            val kb = currentKnowledgeBase()
            kb.setChunkParameters(kbChunkSizeSpinner.value, kbOverlapSpinner.value)
            val result = kb.addDocument(chosen.toPath())
            Platform.runLater {
                kbAddBtn.isDisable = false
                val existingIdx = kbFiles.indexOfFirst { it.path == result.path }
                if (existingIdx >= 0) kbFiles[existingIdx] = result else kbFiles.add(result)
                refreshKnowledgeBaseStatus()
            }
        }
    }

    private fun onRemoveKnowledgeFile() {
        val selected = kbListView.selectionModel.selectedItem ?: return
        currentKnowledgeBase().removeDocument(selected.path)
        kbFiles.remove(selected)
        refreshKnowledgeBaseStatus()
    }

    private fun onReindexAll() {
        if (kbFiles.isEmpty()) return
        kbReindexBtn.isDisable = true
        kbStatusLabel.text = Strings["setup.ai.kb.reindexing"]
        val paths = kbFiles.map { Path.of(it.path) }
        thread(start = true, isDaemon = true, name = "ai-kb-reindex") {
            val kb = currentKnowledgeBase()
            kb.setChunkParameters(kbChunkSizeSpinner.value, kbOverlapSpinner.value)
            val summary = kb.reindexAll(paths)
            Platform.runLater {
                kbFiles.setAll(summary.files)
                refreshKnowledgeBaseStatus()
                kbReindexBtn.isDisable = false
            }
        }
    }

    // ---------- Provider switching ----------

    private fun onProviderChanged(newKind: ProviderKind) {
        // Stash key of *previous* provider if it changed
        stashCurrentApiKey()

        val isCloud = newKind.isCloud
        apiKeyField.isDisable = !isCloud
        pasteBtn.isDisable = !isCloud
        getKeyBtn.text = if (isCloud) Strings["setup.ai.getKey"] else Strings["setup.ai.installGuide"]
        getKeyBtn.tooltip = Tooltip(ProviderRegistry.consoleUrlFor(newKind))

        // Hidrata key descifrada del provider seleccionado, si existe
        apiKeyField.text = decryptKey(newKind)
        keyFormatHint.text = if (isCloud) {
            Strings.format("setup.ai.keyFormatHint", ApiKeyValidator.expectedPrefix(newKind))
        } else ""

        // Modelos
        val knownModels = ProviderRegistry.modelsFor(newKind)
        modelCombo.items.setAll(knownModels)
        modelCombo.value = selectedModels[newKind.name]
            ?: ProviderRegistry.defaultModelFor(newKind).ifBlank { knownModels.firstOrNull() ?: "" }
        modelCombo.isDisable = false
        refreshModelsBtn.isVisible = newKind.isLocal
        refreshModelsBtn.isManaged = newKind.isLocal

        // Local endpoint
        localEndpointField.isDisable = isCloud
        localEndpointField.text = if (isCloud) {
            ""
        } else {
            localEndpoints[newKind.name]?.ifBlank { ProviderRegistry.defaultEndpointFor(newKind) }
                ?: ProviderRegistry.defaultEndpointFor(newKind)
        }

        // Setup guide
        val guide = guideTextFor(newKind)
        (setupGuide.content as? Label)?.text = guide
        setupGuide.isVisible = guide.isNotBlank()
        setupGuide.isManaged = guide.isNotBlank()

        // Privacy
        privacyLabel.text = if (isCloud) Strings["setup.ai.privacyCloud"] else Strings["setup.ai.privacyLocal"]

        // Test connection siempre habilitado en Fase 2 (cloud y local)
        testBtn.isDisable = false

        testStatus.text = ""
    }

    private fun stashCurrentApiKey() {
        val current = providerCombo.value ?: return
        if (current.isLocal) {
            localEndpoints[current.name] = localEndpointField.text.trim()
            modelCombo.value?.let { selectedModels[current.name] = it }
            return
        }
        val plain = apiKeyField.text.orEmpty()
        if (plain.isBlank()) {
            apiKeys.remove(current.name)
        } else {
            apiKeys[current.name] = SecretCipher.encrypt(plain)
        }
        modelCombo.value?.let { selectedModels[current.name] = it }
    }

    private fun decryptKey(kind: ProviderKind): String {
        val v = apiKeys[kind.name] ?: return ""
        return runCatching { SecretCipher.decrypt(v) }.getOrDefault("")
    }

    // ---------- Test connection ----------

    private fun runTestConnection() {
        val kind = providerCombo.value
        val isCloud = kind.isCloud
        val key = if (isCloud) apiKeyField.text.trim() else ""
        val endpoint = if (isCloud) "" else localEndpointField.text.trim().ifBlank {
            ProviderRegistry.defaultEndpointFor(kind)
        }
        if (isCloud && !ApiKeyValidator.validateFormat(kind, key)) {
            testStatus.text = renderError(LlmError.InvalidKeyFormat)
            return
        }
        val model = modelCombo.value.orEmpty().ifBlank { ProviderRegistry.defaultModelFor(kind) }
        testBtn.isDisable = true
        testStatus.text = Strings["setup.ai.testing"]
        thread(start = true, isDaemon = true, name = "ai-test-connection") {
            val provider = ProviderRegistry.create(kind, key, model, endpoint)
            val result = provider.testConnection(30)
            Platform.runLater { onTestResult(result, model) }
        }
    }

    private fun runRefreshModels() {
        val kind = providerCombo.value
        if (kind.isCloud) return
        val endpoint = localEndpointField.text.trim().ifBlank { ProviderRegistry.defaultEndpointFor(kind) }
        refreshModelsBtn.isDisable = true
        testStatus.text = Strings["setup.ai.refreshing"]
        thread(start = true, isDaemon = true, name = "ai-refresh-models") {
            val provider = ProviderRegistry.create(kind, "", "", endpoint)
            val models = runCatching { provider.discoverModels(10) }.getOrDefault(emptyList())
            Platform.runLater {
                refreshModelsBtn.isDisable = false
                if (models.isEmpty()) {
                    testStatus.text = renderError(LlmError.NoModels)
                    return@runLater
                }
                val previous = modelCombo.value
                modelCombo.items.setAll(models)
                modelCombo.value = previous?.takeIf { it in models } ?: models.first()
                testStatus.text = Strings.format("setup.ai.refreshedModels", models.size)
            }
        }
    }

    private fun onTestResult(result: ConnectionResult, model: String) {
        testBtn.isDisable = false
        if (result.success) {
            testStatus.text = Strings.format(
                "setup.ai.testOk",
                result.provider.name,
                result.model,
                result.latencyMillis
            )
            lastVerifiedAt = System.currentTimeMillis()
            lastVerifiedProvider = result.provider.name
            lastVerifiedModel = result.model
        } else {
            testStatus.text = renderError(result.error ?: LlmError.Other("unknown"))
        }
        refreshStatusBadge()
    }

    private fun renderError(err: LlmError): String {
        val key = "error.ai.${err.code}"
        val msg = Strings[key].takeIf { it != key } ?: err.message
        val detail = when (err) {
            is LlmError.ServerError -> " (HTTP ${err.httpCode})"
            is LlmError.Other -> ": ${err.detail}"
            else -> ""
        }
        return "❌ $msg$detail"
    }

    // ---------- Status badge ----------

    private fun refreshStatusBadge() {
        val kind = providerCombo.value
        val isCloud = kind.isCloud
        val configured = if (isCloud) {
            val k = apiKeys[kind.name]
            k != null && !EncryptedValue.isEmpty(k)
        } else {
            localEndpoints[kind.name].orEmpty().isNotBlank()
        }
        val verified = lastVerifiedAt != null && lastVerifiedProvider == kind.name
        statusBadge.text = when {
            !configured -> Strings["setup.ai.status.notConfigured"]
            !verified -> Strings.format("setup.ai.status.unverified", kind.name)
            else -> Strings.format("setup.ai.status.connected", kind.name, lastVerifiedModel.orEmpty(),
                if (isCloud) Strings["setup.ai.cloud"] else Strings["setup.ai.local"])
        }
    }

    // ---------- Helpers ----------

    private fun openBrowser(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            }
        }
    }

    private fun guideTextFor(kind: ProviderKind): String = when (kind) {
        ProviderKind.CLAUDE -> Strings["setup.ai.guide.claude"]
        ProviderKind.OPENAI -> Strings["setup.ai.guide.openai"]
        ProviderKind.GEMINI -> Strings["setup.ai.guide.gemini"]
        ProviderKind.OLLAMA -> Strings["setup.ai.guide.ollama"]
        ProviderKind.LM_STUDIO -> Strings["setup.ai.guide.lmstudio"]
    }
}
