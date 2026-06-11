package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.fingerprint.FingerprintService
import com.opentermx.mcp.onboarding.OnboardingService
import com.opentermx.mcp.telemetry.TelemetryStore
import com.opentermx.telemetrydb.CatalogRepository
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.FlowPane
import javafx.scene.layout.GridPane

/**
 * Asistente de alta de equipo (Fase 6B): pre-llenado por el fingerprint + el match del
 * catálogo, el operador confirma/corrige y se da de alta. Devuelve el deviceId creado
 * (o null si canceló/falló).
 *
 * Métodos de gestión: pre-marcados según `defaultMethods` del modelo pero TODOS
 * deshabilitados salvo CLI_SSH — habilitar Netmiko/Ansible/REST exige tildarlos
 * conscientemente (error #55). Modelo libre (no en catálogo): se crea con
 * `source='operator'` y `defaultMethods=[CLI_SSH]`.
 */
class OnboardingWizardDialog(
    private val store: TelemetryStore,
    private val service: OnboardingService,
    private val sessionId: SessionId,
    private val report: FingerprintService.FingerprintReport,
) : Dialog<Long?>() {

    private data class BrandItem(val id: Long, val name: String, val vendor: String) {
        override fun toString() = name
    }

    private val brandCombo = ComboBox<BrandItem>()
    private val typeCombo = ComboBox<String>()
    private val modelCombo = ComboBox<String>().apply { isEditable = true }
    private val hostnameField = TextField()
    private val siteField = TextField()
    private val roleCombo = ComboBox<String>().apply { items.addAll(ROLES) }
    private val criticalityCombo = ComboBox<String>().apply { items.addAll("low", "medium", "high", "critical"); value = "medium" }
    private val notesArea = TextArea().apply { prefRowCount = 2; isWrapText = true }
    private val methodChecks = CatalogRepository.MGMT_METHODS.associateWith { CheckBox(it) }
    private val restBaseUrlField = TextField().apply { promptText = "https://host" }
    private val matchInfo = Label().apply { isWrapText = true; maxWidth = 460.0; opacity = 0.8 }
    private val statusLabel = Label().apply { isWrapText = true; maxWidth = 460.0 }

    /** Modelos por marca, para resolver el id en el commit y filtrar el combo. */
    private var modelsByBrand: Map<Long, List<CatalogRepository.ModelRow>> = emptyMap()
    private val suggestion = service.suggestFrom(report.identity, report.roleSuggestion)

    init {
        title = Strings["onboarding.wizard.title"]
        headerText = Strings.format(
            "onboarding.wizard.header",
            SessionRegistry.metadataOf(sessionId)?.host ?: report.identity.hostname ?: "?",
        )

        // CLI_SSH siempre on y bloqueado; el resto, opt-in.
        methodChecks.getValue("CLI_SSH").apply { isSelected = true; isDisable = true }
        methodChecks.getValue("REST_API").selectedProperty().addListener { _, _, on ->
            restBaseUrlField.isDisable = !on
        }
        restBaseUrlField.isDisable = true

        val grid = GridPane().apply {
            hgap = 10.0
            vgap = 6.0
            padding = Insets(16.0)
            var r = 0
            add(matchInfo, 0, r, 2, 1); r++
            add(Label(Strings["onboarding.wizard.brand"] + ":"), 0, r); add(brandCombo, 1, r); r++
            add(Label(Strings["onboarding.wizard.type"] + ":"), 0, r); add(typeCombo, 1, r); r++
            add(Label(Strings["onboarding.wizard.model"] + ":"), 0, r); add(modelCombo, 1, r); r++
            add(Label(Strings["onboarding.wizard.hostname"] + ":"), 0, r); add(hostnameField, 1, r); r++
            add(Label(Strings["onboarding.wizard.site"] + ":"), 0, r); add(siteField, 1, r); r++
            add(Label(Strings["onboarding.wizard.role"] + ":"), 0, r); add(roleCombo, 1, r); r++
            add(Label(Strings["onboarding.wizard.criticality"] + ":"), 0, r); add(criticalityCombo, 1, r); r++
            add(Label(Strings["onboarding.wizard.notes"] + ":"), 0, r); add(notesArea, 1, r); r++
            add(Label(Strings["onboarding.wizard.methods"] + ":"), 0, r)
            add(FlowPane(8.0, 4.0).apply { children.addAll(methodChecks.values); prefWrapLength = 360.0 }, 1, r); r++
            add(Label(Strings["onboarding.wizard.restBaseUrl"] + ":"), 0, r); add(restBaseUrlField, 1, r); r++
            add(statusLabel, 0, r, 2, 1)
        }
        dialogPane.content = grid
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)

        brandCombo.valueProperty().addListener { _, _, brand -> if (brand != null) refreshModelsFor(brand.id) }
        prefillStatic()
        loadCatalog()

        setResultConverter { btn -> if (btn == ButtonType.OK) commit() else null }
    }

    // ------------------------------------------------------------------ pre-llenado

    private fun prefillStatic() {
        hostnameField.text = report.identity.hostname ?: SessionRegistry.metadataOf(sessionId)?.host.orEmpty()
        roleCombo.value = suggestion.roleSuggestion ?: "unknown"
        // Métodos default del modelo: se RESALTAN (no se tildan) salvo CLI_SSH.
        matchInfo.text = if (suggestion.catalogModelName != null) {
            Strings.format(
                "onboarding.wizard.matched",
                suggestion.brandName, suggestion.catalogModelName, suggestion.confidence,
                suggestion.matchedText ?: "",
            )
        } else {
            Strings.format("onboarding.wizard.noMatch", suggestion.model ?: "?", suggestion.confidence)
        }
        suggestion.defaultMethods.filter { it != "CLI_SSH" }.forEach { method ->
            methodChecks[method]?.text = "$method ${Strings["onboarding.wizard.suggested"]}"
        }
        val host = SessionRegistry.metadataOf(sessionId)?.host
        if (suggestion.defaultMethods.contains("REST_API") && host != null) {
            restBaseUrlField.text = "https://$host"
        }
    }

    private fun loadCatalog() {
        io({
            val db = store.db() ?: error(Strings["onboarding.wizard.dbUnavailable"])
            val brands = db.catalog.listBrands().map {
                BrandItem((it["id"] as Number).toLong(), it["name"].toString(), it["vendor"].toString())
            }
            val types = db.catalog.listDeviceTypes()
            val models = db.catalog.listModels().groupBy { it.brandId }
            Triple(brands, types, models)
        }) { (brands, types, models) ->
            typeCombo.items.setAll(types)
            brandCombo.items.setAll(brands)
            modelsByBrand = models
            // Selección sugerida.
            typeCombo.value = suggestion.deviceType ?: typeCombo.items.firstOrNull()
            val brand = brands.firstOrNull { it.id == suggestion.brandId } ?: brands.firstOrNull()
            if (brand != null) {
                brandCombo.value = brand
                refreshModelsFor(brand.id)
            }
            if (suggestion.catalogModelName != null) modelCombo.value = suggestion.catalogModelName
            else if (suggestion.model != null) modelCombo.value = suggestion.model
        }
    }

    private fun refreshModelsFor(brandId: Long) {
        val names = modelsByBrand[brandId]?.map { it.name } ?: emptyList()
        val current = modelCombo.value
        modelCombo.items.setAll(names)
        if (current != null) modelCombo.value = current
    }

    // ------------------------------------------------------------------ commit

    /** Resuelve el modelo (existente o libre→operator) y da de alta. Bloquea el FX brevemente. */
    private fun commit(): Long? {
        val metadata = SessionRegistry.metadataOf(sessionId) ?: return null
        val brand = brandCombo.value
        val modelName = modelCombo.value?.trim().orEmpty()
        val enabledMethods = methodChecks.filterValues { it.isSelected }.keys.toList()

        return runCatching {
            val db = store.db() ?: error(Strings["onboarding.wizard.dbUnavailable"])
            // Resolver/crear el catalog model. Libre (no en catálogo) => operator + CLI_SSH.
            val catalogModelId = when {
                modelName.isEmpty() || brand == null -> null
                else -> modelsByBrand[brand.id]?.firstOrNull { it.name.equals(modelName, ignoreCase = true) }?.id
                    ?: db.catalog.saveOperatorModel(
                        brandId = brand.id,
                        deviceType = typeCombo.value ?: "unknown",
                        name = modelName,
                        family = suggestion.family,
                        matchPatterns = emptyList(),
                        defaultMethods = listOf("CLI_SSH"),
                    )
            }
            service.commit(
                OnboardingService.Commit(
                    metadata = metadata,
                    identity = report.identity.takeIf { report.matchedProbeId != null },
                    hostname = hostnameField.text.trim().ifEmpty { metadata.host ?: "device" },
                    vendor = report.identity.vendor,
                    site = siteField.text,
                    role = roleCombo.value ?: "unknown",
                    criticality = criticalityCombo.value ?: "medium",
                    notes = notesArea.text.trim().ifEmpty { null },
                    catalogModelId = catalogModelId,
                    enabledMethods = enabledMethods,
                    probeId = report.matchedProbeId,
                    traceId = report.traceId,
                    rawExcerpt = report.rawExcerpt,
                )
            ) ?: error("commit devolvió null")
        }.getOrElse { e ->
            statusLabel.text = e.message ?: e.javaClass.simpleName
            // Mantener el diálogo abierto ante error: consumimos el OK devolviendo… null
            // cierra el diálogo; preferimos avisar. JavaFX ya cerró con OK, así que el
            // caller verá null y no recargará — el operador puede reintentar desde la UI.
            null
        }
    }

    private fun <T> io(block: () -> T, onSuccess: (T) -> Unit) {
        Thread({
            val result = runCatching(block)
            Platform.runLater {
                result.onSuccess(onSuccess).onFailure { e -> statusLabel.text = e.message ?: e.javaClass.simpleName }
            }
        }, "onboarding-io").apply { isDaemon = true }.start()
    }

    private companion object {
        val ROLES = listOf("switch", "router", "firewall", "access_point", "wireless_controller", "server", "unknown")
    }
}
