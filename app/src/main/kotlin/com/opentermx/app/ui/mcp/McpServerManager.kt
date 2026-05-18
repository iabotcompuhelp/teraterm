package com.opentermx.app.ui.mcp

import com.opentermx.app.settings.AiAssistantSettings
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
    @Volatile private var sessionLauncherProvider: (() -> SessionLauncher?) = { null }
    @Volatile private var credentialStoreProvider: (() -> CredentialStore) = { CredentialStore.Empty }

    /**
     * Configura los proveedores de contexto. Llamarse una sola vez al arrancar `MainWindow`,
     * antes de cualquier `applySettings`.
     *
     * `sessionLauncher` y `credentialStore` son opcionales para no romper tests que solo levantan
     * el manager sin GUI; si no se configuran, la tool `open_session` devolverá `Failure` con un
     * mensaje claro indicando que la integración no está cableada.
     */
    fun configure(
        owner: () -> Window?,
        settings: () -> AiAssistantSettings,
        sessionLauncher: () -> SessionLauncher? = { null },
        credentialStore: () -> CredentialStore = { SettingsCredentialStore(settings) },
    ) {
        this.ownerProvider = owner
        this.settingsProvider = settings
        this.sessionLauncherProvider = sessionLauncher
        this.credentialStoreProvider = credentialStore
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
        // Phase 3 Fase 1: registry de operations persistido en `~/.opentermx/operations/`.
        // Se construye una vez por server start; recovery automático al cargar.
        val operationRegistry = com.opentermx.mcp.operation.OperationRegistry(
            store = com.opentermx.mcp.operation.FsOperationStore(),
        )
        val handlers = listOf(
            ListSessionsHandler(),
            InspectSessionHandler(redactor),
            SearchKnowledgeBaseHandler { KnowledgeBaseHolder.get(settingsProvider()) },
            ProposeCommandsHandler(approvalGate, redactor = redactor),
            ListMacrosHandler(),
            RunMacroHandler(approvalGate),
            OpenSessionHandler(approvalGate, resolveSessionOpener()),
            CloseSessionHandler(approvalGate),
            ReadAuditLogHandler(redactor = redactor),
            TailSessionHandler(tailManager),
            com.opentermx.mcp.handlers.StartOperationHandler(operationRegistry),
            com.opentermx.mcp.handlers.EndOperationHandler(operationRegistry),
            com.opentermx.mcp.handlers.CurrentOperationHandler(operationRegistry),
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
            resourceProvider = com.opentermx.mcp.OpenTermXResources(redactor = redactor),
            operationRegistry = operationRegistry,
        )
    }

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