package com.opentermx.app.ui.dialog

import com.opentermx.ai.ProviderRegistry
import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.AiAssistantSettings
import com.opentermx.app.ui.ai.ModelHandoffService
import com.opentermx.common.ai.ProviderKind
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.Alert
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.layout.GridPane
import javafx.scene.layout.Priority
import javafx.stage.Window
import kotlin.concurrent.thread

/** Diálogo de dos pasos: generar preview factual y confirmar el cambio de proveedor. */
class ModelHandoffDialog(
    owner: Window?,
    private val settings: AiAssistantSettings,
    private val loadPreview: suspend (ProviderKind, String, String) -> ModelHandoffService.Preview,
) : Dialog<ModelHandoffService.Preview>() {
    private val provider = ComboBox(FXCollections.observableArrayList(ProviderKind.entries)).apply {
        value = ProviderKind.entries.firstOrNull { it != settings.providerKind() } ?: settings.providerKind()
        maxWidth = Double.MAX_VALUE
    }
    private val model = ComboBox<String>().apply { isEditable = true; maxWidth = Double.MAX_VALUE }
    private val reason = TextArea().apply { prefRowCount = 2; isWrapText = true }
    private val previewArea = TextArea().apply { isEditable = false; isWrapText = false; prefRowCount = 18 }
    private val status = Label()
    private var preview: ModelHandoffService.Preview? = null

    init {
        initOwner(owner)
        title = Strings["ai.handoff.title"]
        headerText = Strings["ai.handoff.header"]
        val previewType = ButtonType(Strings["ai.handoff.preview"], ButtonBar.ButtonData.OTHER)
        val confirmType = ButtonType(Strings["ai.handoff.confirm"], ButtonBar.ButtonData.OK_DONE)
        dialogPane.buttonTypes.addAll(previewType, confirmType, ButtonType.CANCEL)
        dialogPane.prefWidth = 760.0
        dialogPane.content = GridPane().apply {
            hgap = 10.0; vgap = 8.0; padding = Insets(12.0)
            add(Label(Strings["ai.handoff.provider"]), 0, 0); add(provider, 1, 0)
            add(Label(Strings["ai.handoff.model"]), 0, 1); add(model, 1, 1)
            add(Label(Strings["ai.handoff.reason"]), 0, 2); add(reason, 1, 2)
            add(status, 0, 3, 2, 1); add(previewArea, 0, 4, 2, 1)
            GridPane.setHgrow(provider, Priority.ALWAYS); GridPane.setHgrow(model, Priority.ALWAYS)
            GridPane.setHgrow(previewArea, Priority.ALWAYS); GridPane.setVgrow(previewArea, Priority.ALWAYS)
        }
        provider.valueProperty().addListener { _, _, value -> refreshModels(value) }
        refreshModels(provider.value)

        val confirmButton = dialogPane.lookupButton(confirmType)
        confirmButton.isDisable = true
        dialogPane.lookupButton(previewType).addEventFilter(javafx.event.ActionEvent.ACTION) { event ->
            event.consume()
            val target = provider.value ?: return@addEventFilter
            val targetModel = model.editor.text.trim().ifBlank { model.value.orEmpty() }
            if (targetModel.isBlank()) {
                status.text = Strings["ai.handoff.modelRequired"]
                return@addEventFilter
            }
            confirmButton.isDisable = true
            status.text = Strings["ai.handoff.loading"]
            thread(start = true, isDaemon = true, name = "ai-handoff-preview") {
                runCatching { kotlinx.coroutines.runBlocking { loadPreview(target, targetModel, reason.text.trim()) } }
                    .onSuccess { loaded -> Platform.runLater {
                        preview = loaded
                        previewArea.text = loaded.rawJson
                        status.text = Strings.format("ai.handoff.summary", loaded.journalEvents, loaded.evidenceCount)
                        confirmButton.isDisable = false
                    } }
                    .onFailure { error -> Platform.runLater {
                        status.text = error.message ?: Strings["ai.handoff.failed"]
                    } }
            }
        }
        setResultConverter { button -> if (button == confirmType) preview else null }
    }

    private fun refreshModels(kind: ProviderKind) {
        val models = ProviderRegistry.modelsFor(kind)
        model.items.setAll(models)
        val selected = settings.modelFor(kind).orEmpty().ifBlank { ProviderRegistry.defaultModelFor(kind) }
        model.value = selected.takeIf { it.isNotBlank() } ?: models.firstOrNull()
        model.editor.text = model.value.orEmpty()
        preview = null
        dialogPane.buttonTypes.firstOrNull { it.buttonData == ButtonBar.ButtonData.OK_DONE }
            ?.let { dialogPane.lookupButton(it).isDisable = true }
    }
}
