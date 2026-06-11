package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.mcp.telemetry.TelemetryStore
import com.opentermx.telemetrydb.CatalogPackImporter
import com.opentermx.telemetrydb.CatalogRepository
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.FlowPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.FileChooser

/**
 * `Setup → Catálogo de equipos…` (Fase 6A): marcas/tipos/modelos administrables por
 * las DOS vías del spec — packs YAML (importar/exportar) y alta manual con
 * `source='operator'` (que los packs jamás pisan, error #52).
 *
 * I/O de BD siempre en thread propio (error #19), igual que DeviceProfilesDialog.
 */
class CatalogDialog(
    private val store: TelemetryStore,
) : Dialog<Unit>() {

    private val brandCombo = ComboBox<BrandItem>()
    private val modelList = ListView<CatalogRepository.ModelRow>().apply {
        prefWidth = 240.0
        setCellFactory {
            object : ListCell<CatalogRepository.ModelRow>() {
                override fun updateItem(item: CatalogRepository.ModelRow?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null
                    else item.name + "  [" + item.source.removePrefix("pack:") + "]"
                }
            }
        }
    }

    private data class BrandItem(val id: Long, val name: String, val vendor: String) {
        override fun toString() = "$name ($vendor)"
    }

    private val nameField = TextField()
    private val typeCombo = ComboBox<String>()
    private val familyField = TextField()
    private val patternsArea = TextArea().apply {
        prefRowCount = 3
        promptText = Strings["setup.catalog.patterns.prompt"]
    }
    private val methodChecks = CatalogRepository.MGMT_METHODS.associateWith { CheckBox(it) }
    private val metadataArea = TextArea().apply {
        prefRowCount = 4
        promptText = "{ }"
        styleClass += "mono"
    }
    private val statusLabel = Label().apply { isWrapText = true; maxWidth = 480.0 }

    private val saveButton = Button(Strings["setup.catalog.save"]).apply { setOnAction { saveModel() } }
    private val newModelButton = Button(Strings["setup.catalog.newModel"]).apply { setOnAction { clearModelForm() } }
    private val deleteButton = Button(Strings["setup.catalog.deleteModel"]).apply { setOnAction { deleteModel() } }

    init {
        title = Strings["setup.catalog.title"]
        headerText = Strings["setup.catalog.header"]

        val topBar = HBox(
            8.0,
            Label(Strings["setup.catalog.brand"] + ":"), brandCombo,
            Button(Strings["setup.catalog.newBrand"]).apply { setOnAction { createBrand() } },
            Button(Strings["setup.catalog.importPack"]).apply { setOnAction { importPack() } },
            Button(Strings["setup.catalog.exportBrand"]).apply { setOnAction { exportBrand() } },
        )

        val form = GridPane().apply {
            hgap = 10.0
            vgap = 6.0
            padding = Insets(0.0, 0.0, 0.0, 12.0)
            var r = 0
            add(Label(Strings["setup.catalog.model"] + ":"), 0, r); add(nameField, 1, r); r++
            add(Label(Strings["setup.catalog.type"] + ":"), 0, r); add(typeCombo, 1, r); r++
            add(Label(Strings["setup.catalog.family"] + ":"), 0, r); add(familyField, 1, r); r++
            add(Label(Strings["setup.catalog.patterns"] + ":"), 0, r); add(patternsArea, 1, r); r++
            add(Label(Strings["setup.catalog.methods"] + ":"), 0, r)
            add(FlowPane(8.0, 4.0).apply { children.addAll(methodChecks.values); prefWrapLength = 320.0 }, 1, r); r++
            add(Label(Strings["setup.catalog.metadata"] + ":"), 0, r); add(metadataArea, 1, r); r++
            add(HBox(8.0, saveButton, newModelButton, deleteButton), 1, r)
        }

        dialogPane.content = VBox(
            10.0,
            topBar,
            HBox(8.0, modelList, form).apply { HBox.setHgrow(form, Priority.ALWAYS) },
            statusLabel,
        ).apply { padding = Insets(12.0) }
        dialogPane.buttonTypes.setAll(ButtonType.CLOSE)
        setResultConverter { }

        brandCombo.valueProperty().addListener { _, _, brand -> if (brand != null) loadModels(brand.id, null) }
        modelList.selectionModel.selectedItemProperty().addListener { _, _, model ->
            if (model != null) loadModelForm(model)
        }
        refreshBrands(selectName = null)
    }

    // ------------------------------------------------------------------ data flow

    private fun refreshBrands(selectName: String?) {
        io({
            val db = store.db() ?: error(Strings["setup.catalog.dbUnavailable"])
            db.catalog.listBrands().map {
                BrandItem((it["id"] as Number).toLong(), it["name"].toString(), it["vendor"].toString())
            } to db.catalog.listDeviceTypes()
        }) { (brands, types) ->
            typeCombo.items.setAll(types)
            brandCombo.items.setAll(brands)
            val toSelect = brands.firstOrNull { it.name == selectName } ?: brands.firstOrNull()
            if (toSelect != null) brandCombo.value = toSelect else clearModelForm()
        }
    }

    private fun loadModels(brandId: Long, selectModelName: String?) {
        io({
            val db = store.db() ?: error(Strings["setup.catalog.dbUnavailable"])
            db.catalog.listModels(brandId)
        }) { models ->
            modelList.items.setAll(models)
            val toSelect = models.firstOrNull { it.name == selectModelName } ?: models.firstOrNull()
            if (toSelect != null) modelList.selectionModel.select(toSelect) else clearModelForm()
        }
    }

    private fun loadModelForm(model: CatalogRepository.ModelRow) {
        nameField.text = model.name
        typeCombo.value = model.deviceType
        familyField.text = model.family.orEmpty()
        patternsArea.text = model.matchPatterns.joinToString("\n")
        methodChecks.forEach { (method, check) -> check.isSelected = method in model.defaultMethods }
        metadataArea.text = model.metadataJson
        statusLabel.text = if (model.source == "operator") Strings["setup.catalog.sourceOperator"]
        else Strings.format("setup.catalog.sourcePack", model.source.removePrefix("pack:"))
    }

    private fun clearModelForm() {
        modelList.selectionModel.clearSelection()
        nameField.clear(); familyField.clear(); patternsArea.clear()
        metadataArea.text = "{}"
        typeCombo.value = typeCombo.items.firstOrNull()
        methodChecks.forEach { (method, check) -> check.isSelected = method == "CLI_SSH" }
        statusLabel.text = ""
    }

    /** Guardado SIEMPRE como `source='operator'` — la edición sobrevive a los packs. */
    private fun saveModel() {
        val brand = brandCombo.value ?: return
        val name = nameField.text.trim()
        if (name.isEmpty()) {
            statusLabel.text = Strings["setup.catalog.nameRequired"]
            return
        }
        val metadataJson = metadataArea.text.trim().ifEmpty { "{}" }
        // Validaciones baratas ANTES de tocar la BD: JSON parseable y regex compilables.
        runCatching {
            com.fasterxml.jackson.databind.ObjectMapper().readTree(metadataJson)
        }.onFailure {
            statusLabel.text = Strings.format("setup.catalog.badMetadata", it.message?.take(120))
            return
        }
        val patterns = patternsArea.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        patterns.forEach { pattern ->
            runCatching { Regex(pattern) }.onFailure {
                statusLabel.text = Strings.format("setup.catalog.badPattern", pattern)
                return
            }
        }
        val methods = methodChecks.filterValues { it.isSelected }.keys.toList()
        io({
            val db = store.db() ?: error(Strings["setup.catalog.dbUnavailable"])
            db.catalog.saveOperatorModel(
                brandId = brand.id,
                deviceType = typeCombo.value ?: "unknown",
                name = name,
                family = familyField.text,
                matchPatterns = patterns,
                defaultMethods = methods,
                metadataJson = metadataJson,
            ) ?: error("saveOperatorModel devolvió null")
        }) {
            statusLabel.text = Strings.format("setup.catalog.saved", name)
            loadModels(brand.id, selectModelName = name)
        }
    }

    private fun deleteModel() {
        val brand = brandCombo.value ?: return
        val model = modelList.selectionModel.selectedItem ?: return
        val confirm = javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION).apply {
            title = Strings["setup.catalog.deleteModel"]
            headerText = Strings.format("setup.catalog.deleteConfirm", model.name)
            initOwner(dialogPane.scene.window)
        }
        if (confirm.showAndWait().orElse(null) != ButtonType.OK) return
        io({
            val db = store.db() ?: error(Strings["setup.catalog.dbUnavailable"])
            check(db.catalog.deleteModel(model.id))
        }) { loadModels(brand.id, null) }
    }

    private fun createBrand() {
        val nameField = TextField()
        val vendorCombo = ComboBox<String>().apply {
            items.addAll(com.opentermx.netparsers.Vendor.entries.map { it.name })
            value = "UNKNOWN"
        }
        val dialog = Dialog<Pair<String, String>>().apply {
            title = Strings["setup.catalog.newBrand"]
            dialogPane.content = GridPane().apply {
                hgap = 10.0; vgap = 8.0; padding = Insets(16.0)
                add(Label(Strings["setup.catalog.brandName"] + ":"), 0, 0); add(nameField, 1, 0)
                add(Label(Strings["setup.catalog.brandVendor"] + ":"), 0, 1); add(vendorCombo, 1, 1)
            }
            dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
            setResultConverter { btn ->
                if (btn == ButtonType.OK && nameField.text.isNotBlank()) {
                    nameField.text.trim() to (vendorCombo.value ?: "UNKNOWN")
                } else null
            }
            initOwner(dialogPane.scene.window)
        }
        val (name, vendor) = dialog.showAndWait().orElse(null) ?: return
        io({
            val db = store.db() ?: error(Strings["setup.catalog.dbUnavailable"])
            db.catalog.createBrand(name, vendor) ?: error(Strings["setup.catalog.brandExists"])
        }) { refreshBrands(selectName = name) }
    }

    /** Copia el YAML a `~/.opentermx/catalog/` (queda para futuros arranques) e importa. */
    private fun importPack() {
        val file = FileChooser().apply {
            title = Strings["setup.catalog.importPack"]
            extensionFilters += FileChooser.ExtensionFilter("Catalog pack (*.yaml)", "*.yaml", "*.yml")
        }.showOpenDialog(dialogPane.scene.window) ?: return
        io({
            val db = store.db() ?: error(Strings["setup.catalog.dbUnavailable"])
            Files.createDirectories(CatalogPackImporter.USER_CATALOG_DIR)
            val target = CatalogPackImporter.USER_CATALOG_DIR.resolve(file.name)
            Files.copy(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            CatalogPackImporter(db).importPack(Files.readString(target), file.name)
        }) { result ->
            statusLabel.text = if (result.ok) {
                Strings.format(
                    "setup.catalog.imported",
                    result.brand, result.created, result.updated, result.skippedOperator,
                )
            } else {
                Strings.format("setup.catalog.importRejected", result.errors.joinToString("; ").take(300))
            }
            refreshBrands(selectName = result.brand)
        }
    }

    private fun exportBrand() {
        val brand = brandCombo.value ?: return
        val file = FileChooser().apply {
            title = Strings["setup.catalog.exportBrand"]
            initialFileName = brand.name.lowercase().replace(Regex("""[^a-z0-9]+"""), "-") + "-pack.yaml"
            extensionFilters += FileChooser.ExtensionFilter("Catalog pack (*.yaml)", "*.yaml")
        }.showSaveDialog(dialogPane.scene.window) ?: return
        io({
            val db = store.db() ?: error(Strings["setup.catalog.dbUnavailable"])
            val yaml = CatalogPackImporter(db).exportBrandPack(brand.name)
                ?: error(Strings["setup.catalog.exportEmpty"])
            Files.writeString(file.toPath(), yaml)
            file.name
        }) { statusLabel.text = Strings.format("setup.catalog.exported", it) }
    }

    /** I/O de BD en thread propio; resultado o error al hilo JavaFX. */
    private fun <T> io(block: () -> T, onSuccess: (T) -> Unit) {
        Thread({
            val result = runCatching(block)
            Platform.runLater {
                result.onSuccess(onSuccess).onFailure { e ->
                    statusLabel.text = e.message ?: e.javaClass.simpleName
                }
            }
        }, "catalog-io").apply { isDaemon = true }.start()
    }
}
