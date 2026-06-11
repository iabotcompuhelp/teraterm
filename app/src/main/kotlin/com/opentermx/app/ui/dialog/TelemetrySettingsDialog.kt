package com.opentermx.app.ui.dialog

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.DatabaseSettings
import com.opentermx.app.settings.MonitoringIntegrationSetting
import com.opentermx.common.crypto.EncryptedValue
import com.opentermx.common.crypto.SecretCipher
import com.opentermx.telemetrydb.DbConfig
import com.opentermx.telemetrydb.TelemetryDb
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
import javafx.scene.control.PasswordField
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority

/**
 * `Setup → Telemetría y monitoreo…`: edita las secciones `database` (Fase 3) y
 * `monitoringIntegrations` (Fase 4) que hasta ahora se tocaban a mano en settings.json.
 *
 * Secretos: mismo patrón que AiAssistantDialog — el field se pre-carga con el plaintext
 * descifrado; si el usuario no lo modifica se preserva el [EncryptedValue] anterior,
 * si lo cambia se re-cifra con [SecretCipher], y vacío persiste `null` (cae a la
 * variable de entorno correspondiente).
 */
class TelemetrySettingsDialog(
    initialDb: DatabaseSettings,
    initialIntegrations: List<MonitoringIntegrationSetting>,
) : Dialog<TelemetrySettingsDialog.Result>() {

    data class Result(
        val database: DatabaseSettings,
        val integrations: List<MonitoringIntegrationSetting>,
    )

    // ------------------------------------------------------------------ tab BD

    private val dbEnabledCheck = CheckBox(Strings["setup.telemetry.db.enabled"]).apply {
        isSelected = initialDb.enabled
    }
    private val hostField = TextField(initialDb.host)
    private val portSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 65535, initialDb.port, 1)
        isEditable = true
    }
    private val databaseField = TextField(initialDb.database)
    private val usernameField = TextField(initialDb.username)
    private val dbPasswordStash: EncryptedValue? = initialDb.password
    private val dbPasswordField = PasswordField().apply {
        text = dbPasswordStash?.let { decryptOrNull(it) }.orEmpty()
        promptText = Strings["setup.telemetry.db.passwordPrompt"]
    }
    private val schedulerCheck = CheckBox(Strings["setup.telemetry.db.scheduler"]).apply {
        isSelected = initialDb.schedulerEnabled
    }
    private val pollSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 120, initialDb.pollIntervalMinutes, 1)
        isEditable = true
        disableProperty().bind(schedulerCheck.selectedProperty().not())
    }
    private val retentionSpinner = Spinner<Int>().apply {
        valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(1, 3650, initialDb.retentionDays, 10)
        isEditable = true
    }
    private val testButton = Button(Strings["setup.telemetry.db.test"]).apply {
        setOnAction { testConnection() }
    }
    private val dbStatusLabel = Label().apply { isWrapText = true; maxWidth = 440.0 }

    // ------------------------------------------------------------------ tab integraciones

    /** Copia editable de una integración; conserva el token cifrado original (stash). */
    private inner class Draft(setting: MonitoringIntegrationSetting) {
        var kind: String = setting.kind
        var name: String = setting.name
        var baseUrl: String = setting.baseUrl
        var verifyTls: Boolean = setting.verifyTls
        var apiVersionOverride: String? = setting.apiVersionOverride
        var tokenStash: EncryptedValue? = setting.token
        var tokenPlain: String = setting.token?.let { decryptOrNull(it) }.orEmpty()

        fun toSetting() = MonitoringIntegrationSetting(
            kind = kind,
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            verifyTls = verifyTls,
            apiVersionOverride = apiVersionOverride?.trim()?.ifEmpty { null },
            token = stashToken(tokenPlain, tokenStash),
        )

        override fun toString() = name.ifBlank { Strings["setup.telemetry.int.unnamed"] }
    }

    private val integrationList = ListView<Draft>().apply {
        prefWidth = 190.0
        items.addAll(initialIntegrations.map { Draft(it) })
        setCellFactory {
            object : ListCell<Draft>() {
                override fun updateItem(item: Draft?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else "${item.toString()}  [${item.kind}]"
                }
            }
        }
    }
    private val kindCombo = ComboBox<String>().apply { items.addAll("ZABBIX", "OPMANAGER") }
    private val nameField = TextField()
    private val baseUrlField = TextField().apply { promptText = "https://zabbix.acme.local" }
    private val tokenField = PasswordField().apply {
        promptText = Strings["setup.telemetry.int.tokenPrompt"]
    }
    private val verifyTlsCheck = CheckBox(Strings["setup.telemetry.int.verifyTls"])
    private val apiVersionField = TextField().apply { promptText = "6.0 / 7.0" }
    private val intStatusLabel = Label().apply { isWrapText = true; maxWidth = 440.0 }

    init {
        title = Strings["setup.telemetry.title"]
        headerText = Strings["setup.telemetry.header"]

        dialogPane.content = TabPane(
            Tab(Strings["setup.telemetry.db.tab"], buildDbTab()).apply { isClosable = false },
            Tab(Strings["setup.telemetry.int.tab"], buildIntegrationsTab()).apply { isClosable = false },
        )
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)

        integrationList.selectionModel.selectedItemProperty().addListener { _, previous, current ->
            previous?.let { saveFormInto(it) }
            if (current != null) loadForm(current) else clearForm()
        }
        setIntegrationFormDisabled(true)

        setResultConverter { btn ->
            if (btn != ButtonType.OK) null else {
                integrationList.selectionModel.selectedItem?.let { saveFormInto(it) }
                Result(
                    database = DatabaseSettings(
                        enabled = dbEnabledCheck.isSelected,
                        host = hostField.text.trim().ifEmpty { "localhost" },
                        port = portSpinner.value ?: 5432,
                        database = databaseField.text.trim().ifEmpty { "opentermx" },
                        username = usernameField.text.trim().ifEmpty { "opentermx" },
                        password = stashToken(dbPasswordField.text, dbPasswordStash),
                        schedulerEnabled = schedulerCheck.isSelected,
                        pollIntervalMinutes = pollSpinner.value ?: 5,
                        retentionDays = retentionSpinner.value ?: 90,
                    ),
                    // Una integración sin nombre Y sin URL es una fila vacía: se descarta.
                    integrations = integrationList.items
                        .filter { it.name.isNotBlank() || it.baseUrl.isNotBlank() }
                        .map { it.toSetting() },
                )
            }
        }
    }

    private fun buildDbTab() = GridPane().apply {
        hgap = 10.0
        vgap = 6.0
        padding = Insets(16.0)
        var r = 0
        add(dbEnabledCheck, 0, r, 2, 1); r++
        add(Label(Strings["setup.telemetry.db.host"] + ":"), 0, r); add(hostField, 1, r); r++
        add(Label(Strings["setup.telemetry.db.port"] + ":"), 0, r); add(portSpinner, 1, r); r++
        add(Label(Strings["setup.telemetry.db.name"] + ":"), 0, r); add(databaseField, 1, r); r++
        add(Label(Strings["setup.telemetry.db.user"] + ":"), 0, r); add(usernameField, 1, r); r++
        add(Label(Strings["setup.telemetry.db.password"] + ":"), 0, r); add(dbPasswordField, 1, r); r++
        add(hint("setup.telemetry.db.passwordHint"), 1, r); r++
        add(schedulerCheck, 0, r, 2, 1); r++
        add(Label(Strings["setup.telemetry.db.pollMinutes"] + ":"), 0, r); add(pollSpinner, 1, r); r++
        add(Label(Strings["setup.telemetry.db.retentionDays"] + ":"), 0, r); add(retentionSpinner, 1, r); r++
        add(testButton, 1, r); r++
        add(dbStatusLabel, 0, r, 2, 1)
    }

    private fun buildIntegrationsTab(): HBox {
        val addButton = Button(Strings["setup.telemetry.int.add"]).apply {
            setOnAction {
                val draft = Draft(MonitoringIntegrationSetting())
                integrationList.items.add(draft)
                integrationList.selectionModel.select(draft)
                nameField.requestFocus()
            }
        }
        val removeButton = Button(Strings["setup.telemetry.int.remove"]).apply {
            setOnAction {
                val selected = integrationList.selectionModel.selectedItem ?: return@setOnAction
                integrationList.items.remove(selected)
                clearForm()
            }
        }
        val form = GridPane().apply {
            hgap = 10.0
            vgap = 6.0
            padding = Insets(0.0, 0.0, 0.0, 12.0)
            var r = 0
            add(Label(Strings["setup.telemetry.int.kind"] + ":"), 0, r); add(kindCombo, 1, r); r++
            add(Label(Strings["setup.telemetry.int.name"] + ":"), 0, r); add(nameField, 1, r); r++
            add(hint("setup.telemetry.int.nameHint"), 1, r); r++
            add(Label(Strings["setup.telemetry.int.baseUrl"] + ":"), 0, r); add(baseUrlField, 1, r); r++
            add(Label(Strings["setup.telemetry.int.token"] + ":"), 0, r); add(tokenField, 1, r); r++
            add(hint("setup.telemetry.int.tokenHint"), 1, r); r++
            add(verifyTlsCheck, 1, r); r++
            add(Label(Strings["setup.telemetry.int.apiVersion"] + ":"), 0, r); add(apiVersionField, 1, r); r++
            add(hint("setup.telemetry.int.apiVersionHint"), 1, r); r++
            add(HBox(8.0, addButton, removeButton), 1, r); r++
            add(intStatusLabel, 0, r, 2, 1)
        }
        return HBox(8.0, integrationList, form).apply {
            padding = Insets(16.0)
            HBox.setHgrow(form, Priority.ALWAYS)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun loadForm(draft: Draft) {
        kindCombo.value = draft.kind
        nameField.text = draft.name
        baseUrlField.text = draft.baseUrl
        tokenField.text = draft.tokenPlain
        verifyTlsCheck.isSelected = draft.verifyTls
        apiVersionField.text = draft.apiVersionOverride.orEmpty()
        setIntegrationFormDisabled(false)
    }

    private fun saveFormInto(draft: Draft) {
        draft.kind = kindCombo.value ?: "ZABBIX"
        draft.name = nameField.text.orEmpty()
        draft.baseUrl = baseUrlField.text.orEmpty()
        draft.tokenPlain = tokenField.text.orEmpty()
        draft.verifyTls = verifyTlsCheck.isSelected
        draft.apiVersionOverride = apiVersionField.text.orEmpty().ifBlank { null }
        integrationList.refresh()
    }

    private fun clearForm() {
        kindCombo.value = null
        nameField.clear(); baseUrlField.clear(); tokenField.clear(); apiVersionField.clear()
        verifyTlsCheck.isSelected = true
        setIntegrationFormDisabled(true)
    }

    private fun setIntegrationFormDisabled(disabled: Boolean) {
        listOf(kindCombo, nameField, baseUrlField, tokenField, verifyTlsCheck, apiVersionField)
            .forEach { it.isDisable = disabled }
    }

    /**
     * Prueba la conexión con los valores del formulario (en thread propio — el hilo
     * JavaFX jamás espera a la BD). OJO: `connect` aplica las migraciones Flyway, así
     * que probar contra una base virgen la deja lista — efecto deseado.
     */
    private fun testConnection() {
        val config = DbConfig(
            host = hostField.text.trim().ifEmpty { "localhost" },
            port = portSpinner.value ?: 5432,
            database = databaseField.text.trim().ifEmpty { "opentermx" },
            username = usernameField.text.trim().ifEmpty { "opentermx" },
            password = dbPasswordField.text.orEmpty()
                .ifEmpty { System.getenv("OPENTERMX_DB_PASSWORD").orEmpty() },
        )
        testButton.isDisable = true
        dbStatusLabel.text = Strings["setup.telemetry.db.testing"]
        Thread({
            val result = TelemetryDb.connect(config)
            val message = result.fold(
                onSuccess = { db -> db.close(); Strings["setup.telemetry.db.testOk"] },
                onFailure = { e ->
                    Strings.format("setup.telemetry.db.testFail", e.message ?: e.javaClass.simpleName)
                },
            )
            Platform.runLater {
                dbStatusLabel.text = message
                testButton.isDisable = false
            }
        }, "telemetry-db-test").apply { isDaemon = true }.start()
    }

    private fun hint(key: String) = Label(Strings[key]).apply {
        isWrapText = true
        maxWidth = 440.0
        opacity = 0.75
    }

    /** Vacío => null (cae a la env var); sin cambios => stash previo; cambiado => re-cifrar. */
    private fun stashToken(typed: String, stash: EncryptedValue?): EncryptedValue? {
        val text = typed.trim()
        if (text.isEmpty()) return null
        val previousPlain = stash?.let { decryptOrNull(it) }
        return if (text == previousPlain) stash else SecretCipher.encrypt(text)
    }

    private fun decryptOrNull(v: EncryptedValue): String? =
        if (EncryptedValue.isEmpty(v)) null
        else runCatching { SecretCipher.decrypt(v).takeIf { it.isNotBlank() } }.getOrNull()
}
