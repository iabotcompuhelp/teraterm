package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.mcp.fingerprint.DeviceProfileViews
import com.opentermx.mcp.telemetry.TelemetryStore
import com.opentermx.telemetrydb.ProfileRepository
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox

/**
 * `Setup → Perfiles de dispositivos…` (Fase 5): el operador CONFIRMA o corrige el rol
 * inferido por el fingerprint (spec 5A: el campo aparece pre-llenado con la inferencia
 * y un indicador "(detectado, confianza: X)") y edita el contexto operativo —
 * criticidad, notas, ventana de mantenimiento, contacto y comandos prohibidos.
 *
 * Semántica del rol (regla de merge 5B-2):
 *  - tildado "confirmar" => `roleSource=OPERATOR`: ningún fingerprint vuelve a pisarlo;
 *  - destildado => el rol vuelve al control del sistema (INFERRED) y la próxima
 *    sugerencia lo actualiza.
 *
 * Toda la I/O contra PostgreSQL corre en un thread propio — el hilo JavaFX jamás
 * espera a la BD (mismo criterio que TelemetryDbManager, error #19).
 */
class DeviceProfilesDialog(
    private val store: TelemetryStore,
    /** Hook post-guardado: invalidar caché de enriquecimiento + regenerar doc RAG. */
    private val onProfileSaved: (hostname: String, mgmtAddress: String?) -> Unit = { _, _ -> },
    /** Hook post-borrado (error #49): eliminar el doc RAG del disco y del índice. */
    private val onDeviceRemoved: (hostname: String, mgmtAddress: String?) -> Unit = { _, _ -> },
) : Dialog<Unit>() {

    private data class DeviceRow(
        val id: Long,
        val hostname: String,
        val mgmtAddress: String?,
        val vendor: String,
        val model: String?,
        val osVersion: String?,
        val site: String?,
        val rawRole: String?,
    ) {
        override fun toString() = hostname
    }

    private val deviceList = ListView<DeviceRow>().apply {
        prefWidth = 230.0
        setCellFactory {
            object : ListCell<DeviceRow>() {
                override fun updateItem(item: DeviceRow?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null
                    else item.hostname + (item.model?.let { "  ($it)" } ?: "")
                }
            }
        }
    }

    private val identityLabel = Label().apply { styleClass += "hint-label" }
    private val roleCombo = ComboBox<String>().apply {
        items.addAll(ROLES)
    }
    private val roleSourceLabel = Label().apply { opacity = 0.75 }
    private val confirmRoleCheck = CheckBox(Strings["setup.deviceProfiles.roleConfirm"])
    private val criticalityCombo = ComboBox<String>().apply {
        items.addAll("low", "medium", "high", "critical")
    }
    private val maintenanceField = TextField()
    private val contactField = TextField()
    private val notesArea = TextArea().apply { prefRowCount = 3; isWrapText = true }
    private val forbiddenArea = TextArea().apply {
        prefRowCount = 3
        promptText = Strings["setup.deviceProfiles.forbidden.prompt"]
    }
    private val saveButton = javafx.scene.control.Button(Strings["setup.deviceProfiles.save"]).apply {
        isDisable = true
        setOnAction { saveCurrent() }
    }
    private val deleteButton = javafx.scene.control.Button(Strings["setup.deviceProfiles.delete"]).apply {
        isDisable = true
        setOnAction { deleteCurrent() }
    }
    private val statusLabel = Label().apply { isWrapText = true; maxWidth = 420.0 }

    /** roleSource del perfil cargado — decide si destildar implica liberar el rol. */
    private var loadedRoleSource: String = "INFERRED"

    init {
        title = Strings["setup.deviceProfiles.title"]
        headerText = Strings["setup.deviceProfiles.header"]

        val form = GridPane().apply {
            hgap = 10.0
            vgap = 6.0
            padding = Insets(0.0, 0.0, 0.0, 12.0)
            var r = 0
            add(identityLabel, 0, r, 2, 1); r++
            add(Label(Strings["setup.deviceProfiles.role"] + ":"), 0, r); add(roleCombo, 1, r); r++
            add(roleSourceLabel, 1, r); r++
            add(confirmRoleCheck, 0, r, 2, 1); r++
            add(Label(Strings["setup.deviceProfiles.criticality"] + ":"), 0, r); add(criticalityCombo, 1, r); r++
            add(Label(Strings["setup.deviceProfiles.maintenanceWindow"] + ":"), 0, r); add(maintenanceField, 1, r); r++
            add(Label(Strings["setup.deviceProfiles.contact"] + ":"), 0, r); add(contactField, 1, r); r++
            add(Label(Strings["setup.deviceProfiles.notes"] + ":"), 0, r); add(notesArea, 1, r); r++
            add(Label(Strings["setup.deviceProfiles.forbidden"] + ":"), 0, r); add(forbiddenArea, 1, r); r++
            add(HBox(8.0, saveButton, deleteButton), 1, r); r++
            add(statusLabel, 0, r, 2, 1)
        }
        setFormDisabled(true)

        dialogPane.content = VBox(
            8.0,
            HBox(8.0, deviceList, form).apply { HBox.setHgrow(form, Priority.ALWAYS) },
        ).apply { padding = Insets(12.0) }
        dialogPane.buttonTypes.setAll(ButtonType.CLOSE)
        setResultConverter { }

        deviceList.selectionModel.selectedItemProperty().addListener { _, _, row ->
            if (row != null) loadProfile(row) else resetForm()
        }
        loadDevices(selectHostname = null)
    }

    // ------------------------------------------------------------------ data flow

    private fun loadDevices(selectHostname: String?) {
        statusLabel.text = ""
        io({
            val db = store.db() ?: error(Strings["setup.deviceProfiles.dbUnavailable"])
            db.devices.list(limit = 500).map { row ->
                DeviceRow(
                    id = (row["id"] as Number).toLong(),
                    hostname = row["hostname"]?.toString().orEmpty(),
                    mgmtAddress = row["mgmt_address"]?.toString(),
                    vendor = row["vendor"]?.toString() ?: "UNKNOWN",
                    model = row["model"] as? String,
                    osVersion = row["os_version"] as? String,
                    site = row["site"] as? String,
                    rawRole = row["role"] as? String,
                )
            }
        }) { rows ->
            deviceList.items.setAll(rows)
            if (rows.isEmpty()) {
                statusLabel.text = Strings["setup.deviceProfiles.noDevices"]
            } else {
                val toSelect = rows.firstOrNull { it.hostname == selectHostname } ?: rows.first()
                deviceList.selectionModel.select(toSelect)
            }
        }
    }

    private fun loadProfile(row: DeviceRow) {
        setFormDisabled(true)
        identityLabel.text = listOfNotNull(
            row.hostname, row.vendor, row.model, row.osVersion, row.site,
        ).joinToString(" — ")
        io({
            val db = store.db() ?: error(Strings["setup.deviceProfiles.dbUnavailable"])
            db.profiles.load(row.id)
        }) { loaded ->
            val record = (loaded as? ProfileRepository.LoadResult.Loaded)?.record
            loadedRoleSource = record?.roleSource ?: "INFERRED"
            val normalizedRole = DeviceProfileViews.normalizeRole(record?.role ?: row.rawRole)
            roleCombo.value = normalizedRole
            confirmRoleCheck.isSelected = loadedRoleSource == "OPERATOR"
            roleSourceLabel.text = if (loadedRoleSource == "OPERATOR") {
                Strings["setup.deviceProfiles.roleByOperator"]
            } else {
                Strings.format(
                    "setup.deviceProfiles.roleDetected",
                    normalizedRole, record?.lastConfidence ?: "LOW",
                )
            }
            criticalityCombo.value = record?.criticality ?: "medium"
            maintenanceField.text = record?.profile?.get("maintenanceWindow") as? String ?: ""
            contactField.text = record?.profile?.get("contact") as? String ?: ""
            notesArea.text = record?.profile?.get("notes") as? String ?: ""
            val forbidden = (record?.profile?.get("capabilities") as? Map<*, *>)
                ?.get("forbidden") as? List<*> ?: emptyList<Any?>()
            forbiddenArea.text = forbidden.joinToString("\n") { it.toString() }
            setFormDisabled(false)
        }
    }

    private fun saveCurrent() {
        val row = deviceList.selectionModel.selectedItem ?: return
        val confirmRole = confirmRoleCheck.isSelected
        val wasOperator = loadedRoleSource == "OPERATOR"
        val role = roleCombo.value
        val criticality = criticalityCombo.value
        val notes = notesArea.text.orEmpty()
        val maintenance = maintenanceField.text.orEmpty()
        val contact = contactField.text.orEmpty()
        val forbidden = forbiddenArea.text.orEmpty()
            .lines().map { it.trim() }.filter { it.isNotEmpty() }

        setFormDisabled(true)
        io({
            val db = store.db() ?: error(Strings["setup.deviceProfiles.dbUnavailable"])
            if (!confirmRole && wasOperator) {
                db.profiles.releaseRoleToInferred(row.id)
            }
            val ok = db.profiles.updateOperatorFields(
                deviceId = row.id,
                role = if (confirmRole) role else null,
                criticality = criticality,
                notes = notes,
                forbidden = forbidden,
                maintenanceWindow = maintenance,
                contact = contact,
            )
            check(ok) { "updateOperatorFields devolvió false" }
        }) {
            onProfileSaved(row.hostname, row.mgmtAddress)
            statusLabel.text = Strings.format("setup.deviceProfiles.saved", row.hostname)
            loadDevices(selectHostname = row.hostname)
        }
    }

    /** Borrado del inventario — destructivo: confirmación explícita antes de tocar la BD. */
    private fun deleteCurrent() {
        val row = deviceList.selectionModel.selectedItem ?: return
        val confirm = javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION).apply {
            title = Strings["setup.deviceProfiles.deleteConfirmTitle"]
            headerText = Strings.format("setup.deviceProfiles.deleteConfirmHeader", row.hostname)
            contentText = Strings["setup.deviceProfiles.deleteConfirmText"]
            initOwner(dialogPane.scene.window)
        }
        if (confirm.showAndWait().orElse(null) != ButtonType.OK) return

        setFormDisabled(true)
        io({
            val db = store.db() ?: error(Strings["setup.deviceProfiles.dbUnavailable"])
            check(db.devices.delete(row.id)) { "delete devolvió false" }
        }) {
            onDeviceRemoved(row.hostname, row.mgmtAddress)
            statusLabel.text = Strings.format("setup.deviceProfiles.deleted", row.hostname)
            loadDevices(selectHostname = null)
        }
    }

    /** Sin selección (lista vacía tras un borrado): campos limpios y deshabilitados. */
    private fun resetForm() {
        identityLabel.text = ""
        roleSourceLabel.text = ""
        roleCombo.value = null
        criticalityCombo.value = null
        confirmRoleCheck.isSelected = false
        maintenanceField.clear(); contactField.clear(); notesArea.clear(); forbiddenArea.clear()
        setFormDisabled(true)
    }

    private fun setFormDisabled(disabled: Boolean) {
        listOf(
            roleCombo, roleSourceLabel, confirmRoleCheck, criticalityCombo,
            maintenanceField, contactField, notesArea, forbiddenArea, saveButton, deleteButton,
        ).forEach { it.isDisable = disabled }
    }

    /** I/O de BD en thread propio; el resultado (o el error) vuelve al hilo JavaFX. */
    private fun <T> io(block: () -> T, onSuccess: (T) -> Unit) {
        Thread({
            val result = runCatching(block)
            Platform.runLater {
                result.onSuccess(onSuccess).onFailure { e ->
                    statusLabel.text = Strings.format(
                        "setup.deviceProfiles.saveError", e.message ?: e.javaClass.simpleName,
                    )
                    setFormDisabled(deviceList.selectionModel.selectedItem == null)
                }
            }
        }, "device-profiles-io").apply { isDaemon = true }.start()
    }

    private companion object {
        /** Enum del schema publicado de `get_device_profile` (Fase 5C). */
        val ROLES = listOf(
            "switch", "router", "firewall", "access_point",
            "wireless_controller", "server", "unknown",
        )
    }
}
