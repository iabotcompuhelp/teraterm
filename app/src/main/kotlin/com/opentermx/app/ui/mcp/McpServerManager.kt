package com.opentermx.app.ui.mcp

import com.opentermx.app.settings.AiAssistantSettings
import com.opentermx.app.settings.AppSettings
import com.opentermx.app.settings.SettingsCredentialStore
import com.opentermx.app.ui.ai.JavaFxApprovalGate
import com.opentermx.app.ui.ai.KnowledgeBaseHolder
import com.opentermx.common.credentials.CredentialStore
import com.opentermx.common.crypto.EncryptedValue
import com.opentermx.common.crypto.SecretCipher
import com.opentermx.mcp.McpServer
import com.opentermx.mcp.handlers.CloseSessionHandler
import com.opentermx.mcp.handlers.InspectSessionHandler
import com.opentermx.mcp.handlers.ListMacrosHandler
import com.opentermx.mcp.handlers.ListSessionsHandler
import com.opentermx.mcp.handlers.OpenSessionHandler
import com.opentermx.mcp.handlers.ProposeCommandsHandler
import com.opentermx.mcp.handlers.ReadAuditLogHandler
import com.opentermx.mcp.handlers.RunMacroHandler
import com.opentermx.mcp.handlers.SearchKnowledgeBaseHandler
import com.opentermx.mcp.handlers.TailSessionHandler
import com.opentermx.mcp.security.TailManager
import com.opentermx.mcp.security.ApprovalGate
import javafx.stage.Window
import org.slf4j.LoggerFactory

/**
 * Singleton de gestión del [McpServer] en el módulo `app`. Equivalente conceptual a
 * `RestApiManager`: configura las dependencias inyectadas (approval gate JavaFX, provider
 * de KnowledgeBase), arranca/para el servidor según settings, y expone el `StateFlow` de
 * estado para que la status bar lo bindee.
 *
 * Tener un singleton evita re-instanciar handlers cada vez que se cambia un setting:
 * basta con `restart()` cuando port/bind/token mutan.
 */
object McpServerManager {

    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var server: McpServer? = null
    @Volatile private var ownerProvider: (() -> Window?) = { null }
    @Volatile private var settingsProvider: (() -> AiAssistantSettings) = { AiAssistantSettings() }
    @Volatile private var appSettingsProvider: (() -> AppSettings) = { AppSettings() }
    @Volatile private var sessionLauncherProvider: (() -> SessionLauncher?) = { null }
    @Volatile private var credentialStoreProvider: (() -> CredentialStore) = { CredentialStore.Empty }

    /**
     * Configura los proveedores de contexto. Llamarse una sola vez al arrancar `MainWindow`,
     * antes de cualquier `applySettings`.
     *
     * `sessionLauncher` y `credentialStore` son opcionales para no romper tests que solo levantan
     * el manager sin GUI; si no se configuran, la tool `open_session` devolverá `Failure` con un
     * mensaje claro indicando que la integración no está cableada.
     *
     * `@Synchronized` (mismo lock que `applySettings`/`stop`) para que los 5 providers
     * se intercambien de forma atómica: sin esto, un `applySettings` concurrente podía
     * construir el server con mezcla de providers viejos y nuevos.
     */
    @Synchronized
    fun configure(
        owner: () -> Window?,
        settings: () -> AiAssistantSettings,
        sessionLauncher: () -> SessionLauncher? = { null },
        credentialStore: () -> CredentialStore = { SettingsCredentialStore(settings) },
        appSettings: () -> AppSettings = { AppSettings(aiAssistant = settings()) },
    ) {
        this.ownerProvider = owner
        this.settingsProvider = settings
        this.sessionLauncherProvider = sessionLauncher
        this.credentialStoreProvider = credentialStore
        this.appSettingsProvider = appSettings
    }

    /**
     * Aplica los settings actuales: arranca si `enabled` y aún no corre; reinicia si
     * cambiaron port/bind/token; para si pasó de enabled a disabled. Es idempotente.
     */
    @Synchronized
    fun applySettings(): Result<Unit> = runCatching {
        val s = migrateLegacyTokenIfNeeded(settingsProvider())
        val current = server
        if (!s.mcpServerEnabled) {
            stopInternal()
            return@runCatching
        }
        val needsAuth = s.mcpServerTokens.any { !it.isExpired() } || !decodeToken(s.mcpServerToken).isNullOrBlank()
        val needsRestart = current != null && (
            current.binding()?.host != s.mcpServerBindAddress ||
                current.binding()?.port != s.mcpServerPort ||
                current.binding()?.hasAuth != needsAuth
            )
        if (current == null || needsRestart) {
            stopInternal()
            val fresh = buildServer()
            try {
                // Si hay multi-token configurado, no usamos legacy token; el verifier hace match.
                val legacyToken = if (s.mcpServerTokens.isEmpty()) decodeToken(s.mcpServerToken) else null
                fresh.start(s.mcpServerPort, s.mcpServerBindAddress, legacyToken)
                server = fresh
            } catch (e: Throwable) {
                server = fresh
                throw e
            }
        }
    }

    /**
     * Si el operador no tocó la nueva lista pero tiene token legacy, lo migra una vez:
     * crea un `McpTokenEntry` con `name="legacy"` apuntando al hash del plaintext actual.
     * Devuelve los settings modificados pero NO los persiste — el flujo normal de Setup
     * lo hace al cerrar el diálogo.
     */
    private fun migrateLegacyTokenIfNeeded(s: AiAssistantSettings): AiAssistantSettings {
        if (s.mcpServerTokens.isNotEmpty()) return s
        val legacy = decodeToken(s.mcpServerToken) ?: return s
        if (legacy.isBlank()) return s
        val entry = com.opentermx.app.settings.McpTokenEntry(
            id = java.util.UUID.randomUUID().toString(),
            name = "legacy",
            hash = com.opentermx.app.settings.McpTokenEntry.hashOf(legacy),
            createdAtMillis = System.currentTimeMillis(),
        )
        log.info("Migrando token legacy a `mcpServerTokens` (id=${entry.id})")
        return s.copy(mcpServerTokens = listOf(entry))
    }

    /**
     * Para el servidor y limpia el estado interno. Llamado desde el shutdown hook de
     * `MainWindow` y al cambiar settings con `enabled=false`.
     */
    @Synchronized
    fun stop() {
        stopInternal()
    }

    /**
     * Acceso al `StateFlow` de estado para bindeo desde la UI. Devuelve `null` si nunca
     * se arrancó el server (todavía no hay nada que observar).
     */
    fun status() = server?.status

    fun binding() = server?.binding()
    fun lastError() = server?.lastError()

    private fun stopInternal() {
        server?.stop()
        server = null
    }

    private fun buildServer(): McpServer {
        val settings = settingsProvider()
        val approvalGate: ApprovalGate = JavaFxApprovalGate(ownerProvider)
        val redactor = com.opentermx.mcp.security.RedactorFactory.fromCustomRules(settings.mcpServerCustomRedactionRules)
        val tailManager = TailManager()
        // Runner ÚNICO del proceso (compartido también con el scheduler de telemetría):
        // el mutex por sesión solo sirve si todos los inyectores usan la misma instancia.
        val commandRunner = TelemetryDbManager.sharedCommandRunner
        // Phase 3 Fase 1: registry de operations persistido en `~/.opentermx/operations/`.
        // Se construye una vez por server start; recovery automático al cargar.
        val operationRegistry = com.opentermx.mcp.operation.OperationRegistry(
            store = com.opentermx.mcp.operation.FsOperationStore(),
        )
        // Phase 3 Fase 3: secret store HMAC para approval tokens. El secret se genera al
        // primer arranque (0600 en POSIX). Lo cacheamos en una lambda para que el
        // ProposeCommandsHandler pueda leer el valor actualizado si alguna vez rotamos.
        val secretStore = com.opentermx.mcp.security.McpSecretStore()
        val secretProvider: () -> ByteArray = { secretStore.loadOrCreate() }
        // Phase 3 Fase 4: snapshot store filesystem-backed.
        val snapshotStore = com.opentermx.mcp.snapshots.FsSnapshotStore()
        // Phase 3 Fase 5: registry de policies in-memory; el operador las carga vía
        // policy_load y persisten mientras viva el server. Restart limpia.
        val policyRegistry = com.opentermx.policy.PolicyRegistry()
        // Phase 3 Fase 2: el InventoryProvider consume `AppSettings.savedConnections`
        // (las entradas que tengan `alias` definido). Resolver lambda = lookup vivo, así
        // las tools de inventory reflejan cambios del Setup → Saved Connections sin
        // necesidad de reiniciar el server.
        val inventoryProvider = SettingsInventoryProvider(appSettingsProvider)
        // Fase 5C: perfiles de dispositivo. Una sola instancia de views (caché TTL del
        // enriquecimiento) compartida por list_sessions, las tools de perfil y resources.
        val fingerprintSettings = appSettingsProvider().fingerprint
        val readonlyValidator = com.opentermx.mcp.security.ReadOnlyCommandValidator.default()
        val profileViews = com.opentermx.mcp.fingerprint.DeviceProfileViews(
            store = TelemetryDbManager.store,
            validator = readonlyValidator,
        )
        val fingerprintService = com.opentermx.mcp.fingerprint.FingerprintService(
            runner = commandRunner,
            validator = readonlyValidator,
            dryRun = fingerprintSettings.dryRun,
            activeProbing = fingerprintSettings.activeProbing,
        )
        val handlers = listOf(
            ListSessionsHandler(views = profileViews),
            InspectSessionHandler(redactor),
            SearchKnowledgeBaseHandler { KnowledgeBaseHolder.get(settingsProvider()) },
            ProposeCommandsHandler(
                approvalGate, redactor = redactor,
                operationRegistry = operationRegistry,
                approvalSecretProvider = secretProvider,
                snapshotStore = snapshotStore,
            ),
            // Lectura ejecutable con whitelist regex por vendor (Fase 1 telemetría). El
            // lambda lee el setting en vivo: togglear el checkbox aplica sin reiniciar.
            com.opentermx.mcp.handlers.RunReadonlyCommandHandler(
                approvalGate,
                allowWithoutApproval = { settingsProvider().mcpServerReadonlyAutoApprove },
                redactor = redactor,
                runner = commandRunner,
            ),
            // Fase 2 telemetría: tools de alto nivel sobre el MISMO runner (el mutex por
            // sesión solo serializa si todos los handlers comparten la instancia).
            com.opentermx.mcp.handlers.GetInterfaceStatsHandler(
                commandRunner, redactor = redactor,
                store = TelemetryDbManager.store, // persist=true inserta en interface_metrics si hay BD
            ),
            com.opentermx.mcp.handlers.GetLinkStatusHandler(commandRunner, redactor = redactor),
            com.opentermx.mcp.handlers.GetBandwidthUtilizationHandler(commandRunner, redactor = redactor),
            // Fase 3: histórico local en PostgreSQL (DB_UNAVAILABLE claro si no hay BD).
            com.opentermx.mcp.handlers.GetDeviceHistoryHandler(TelemetryDbManager.store),
            // Fase 5C: perfiles de dispositivo (identidad + capacidades + topología).
            com.opentermx.mcp.handlers.GetDeviceProfileHandler(TelemetryDbManager.store, profileViews),
            com.opentermx.mcp.handlers.RefreshDeviceFingerprintHandler(
                fingerprintService, TelemetryDbManager.store, profileViews,
            ),
            com.opentermx.mcp.handlers.ListDevicesHandler(TelemetryDbManager.store),
            com.opentermx.mcp.handlers.DiagnoseDeviceContextHandler(TelemetryDbManager.store, profileViews),
            // Fase 4: monitoreo externo read-only (Zabbix/OpManager). El registry lee
            // los settings en vivo — agregar una integración no exige reiniciar.
            com.opentermx.mcp.handlers.ZabbixGetHistoryHandler(::integrationRegistry),
            com.opentermx.mcp.handlers.ZabbixGetActiveProblemsHandler(::integrationRegistry),
            com.opentermx.mcp.handlers.OpManagerGetAlarmsHandler(::integrationRegistry),
            com.opentermx.mcp.handlers.OpManagerGetPerformanceHandler(::integrationRegistry),
            ListMacrosHandler(),
            RunMacroHandler(approvalGate),
            OpenSessionHandler(approvalGate, resolveSessionOpener(), inventoryProvider),
            CloseSessionHandler(approvalGate),
            ReadAuditLogHandler(redactor = redactor),
            TailSessionHandler(tailManager),
            com.opentermx.mcp.handlers.StartOperationHandler(operationRegistry),
            com.opentermx.mcp.handlers.EndOperationHandler(operationRegistry),
            com.opentermx.mcp.handlers.CurrentOperationHandler(operationRegistry),
            com.opentermx.mcp.handlers.InventoryListHandler(inventoryProvider),
            com.opentermx.mcp.handlers.InventoryDescribeHandler(inventoryProvider),
            com.opentermx.mcp.handlers.ComplianceEvaluateHandler(
                operationRegistry,
                secretProvider,
            ),
            com.opentermx.mcp.handlers.SnapshotCreateHandler(snapshotStore, operationRegistry),
            com.opentermx.mcp.handlers.SnapshotDiffHandler(snapshotStore),
            com.opentermx.mcp.handlers.SnapshotCompareToCriteriaHandler(snapshotStore, operationRegistry),
            com.opentermx.mcp.handlers.RollbackProposeHandler(snapshotStore),
            com.opentermx.mcp.handlers.PolicyLoadHandler(policyRegistry),
            com.opentermx.mcp.handlers.PolicyListHandler(policyRegistry),
            com.opentermx.mcp.handlers.PolicyEvaluateHandler(policyRegistry, snapshotStore, inventoryProvider),
            com.opentermx.mcp.handlers.PolicyAuditHandler(policyRegistry, snapshotStore, inventoryProvider),
        )
        val tlsConfig: McpServer.TlsConfig? = if (settings.mcpServerTlsEnabled && !settings.mcpServerKeyStorePath.isNullOrBlank()) {
            val password = decodeToken(settings.mcpServerKeyStorePassword).orEmpty()
            McpServer.TlsConfig(keyStorePath = settings.mcpServerKeyStorePath, keyStorePassword = password)
        } else null
        val tokenVerifier: ((String) -> Boolean)? = if (settings.mcpServerTokens.isNotEmpty()) {
            { plaintext ->
                com.opentermx.app.settings.McpTokenEntry.matchAny(plaintext, settings.mcpServerTokens) != null
            }
        } else null
        return McpServer(
            handlers = handlers,
            verboseLog = settings.mcpServerVerboseLog,
            readOnly = settings.mcpServerReadOnly,
            allowedSessionGlob = settings.mcpServerAllowedSessionGlob,
            rateLimitEnabled = settings.mcpServerRateLimitEnabled,
            tokenVerifier = tokenVerifier,
            tlsConfig = tlsConfig,
            auditLog = com.opentermx.ai.audit.AiAuditLog(),
            redactor = redactor,
            tailManager = tailManager,
            resourceProvider = com.opentermx.mcp.OpenTermXResources(
                redactor = redactor,
                store = TelemetryDbManager.store,
                profileViews = profileViews,
            ),
            operationRegistry = operationRegistry,
        )
    }

    /**
     * Registry de integraciones de monitoreo (Fase 4) construido en vivo desde los
     * settings. El secreto se resuelve recién al usarlo: token cifrado con SecretCipher
     * o env `OPENTERMX_INTEGRATION_<NOMBRE>_TOKEN` — nunca plaintext.
     */
    private fun integrationRegistry(): com.opentermx.integrations.IntegrationRegistry =
        com.opentermx.integrations.IntegrationRegistry { name ->
            val setting = appSettingsProvider().monitoringIntegrations
                .firstOrNull { it.name.equals(name, ignoreCase = true) && it.name.isNotBlank() }
                ?: return@IntegrationRegistry null
            val kind = runCatching {
                com.opentermx.integrations.IntegrationKind.valueOf(setting.kind.uppercase())
            }.getOrNull() ?: return@IntegrationRegistry null
            com.opentermx.integrations.MonitoringIntegration(
                kind = kind,
                name = setting.name,
                baseUrl = setting.baseUrl,
                verifyTls = setting.verifyTls,
                extra = setting.apiVersionOverride?.let { mapOf("apiVersion" to it) } ?: emptyMap(),
                secretProvider = {
                    decodeToken(setting.token)
                        ?: System.getenv(integrationTokenEnvVar(setting.name)).orEmpty()
                },
            )
        }

    private fun integrationTokenEnvVar(name: String): String =
        "OPENTERMX_INTEGRATION_" + name.uppercase().replace(Regex("[^A-Z0-9]"), "_") + "_TOKEN"

    private fun resolveSessionOpener(): com.opentermx.mcp.security.SessionOpener {
        val launcher = sessionLauncherProvider()
            ?: return com.opentermx.mcp.security.SessionOpener.NoOp
        return JavaFxSessionOpener(launcher, credentialStoreProvider())
    }

    private fun decodeToken(value: EncryptedValue?): String? {
        if (value == null || EncryptedValue.isEmpty(value)) return null
        return runCatching { SecretCipher.decrypt(value).takeIf { it.isNotBlank() } }
            .getOrElse {
                log.warn("No se pudo descifrar el token MCP: {}", it.message)
                null
            }
    }
}