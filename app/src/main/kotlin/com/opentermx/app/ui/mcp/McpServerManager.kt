package com.opentermx.app.ui.mcp

import com.opentermx.app.settings.AiAssistantSettings
import com.opentermx.app.ui.ai.JavaFxApprovalGate
import com.opentermx.app.ui.ai.KnowledgeBaseHolder
import com.opentermx.common.crypto.EncryptedValue
import com.opentermx.common.crypto.SecretCipher
import com.opentermx.mcp.McpServer
import com.opentermx.mcp.handlers.InspectSessionHandler
import com.opentermx.mcp.handlers.ListSessionsHandler
import com.opentermx.mcp.handlers.ProposeCommandsHandler
import com.opentermx.mcp.handlers.SearchKnowledgeBaseHandler
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

    /**
     * Configura los proveedores de contexto. Llamarse una sola vez al arrancar `MainWindow`,
     * antes de cualquier `applySettings`.
     */
    fun configure(owner: () -> Window?, settings: () -> AiAssistantSettings) {
        this.ownerProvider = owner
        this.settingsProvider = settings
    }

    /**
     * Aplica los settings actuales: arranca si `enabled` y aún no corre; reinicia si
     * cambiaron port/bind/token; para si pasó de enabled a disabled. Es idempotente.
     */
    @Synchronized
    fun applySettings(): Result<Unit> = runCatching {
        val s = settingsProvider()
        val current = server
        if (!s.mcpServerEnabled) {
            stopInternal()
            return@runCatching
        }
        val tokenPlain = decodeToken(s.mcpServerToken)
        val needsRestart = current != null && (
            current.binding()?.host != s.mcpServerBindAddress ||
                current.binding()?.port != s.mcpServerPort ||
                current.binding()?.hasAuth != !tokenPlain.isNullOrBlank()
            )
        if (current == null || needsRestart) {
            stopInternal()
            val fresh = buildServer()
            try {
                fresh.start(s.mcpServerPort, s.mcpServerBindAddress, tokenPlain)
                server = fresh
            } catch (e: Throwable) {
                // start() ya cambió status a FAILED; lo guardamos para que la status bar
                // pueda mostrar tooltip con el motivo aunque el server no esté en server.
                server = fresh
                throw e
            }
        }
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
        val approvalGate: ApprovalGate = JavaFxApprovalGate(ownerProvider)
        val handlers = listOf(
            ListSessionsHandler(),
            InspectSessionHandler(),
            SearchKnowledgeBaseHandler { KnowledgeBaseHolder.get(settingsProvider()) },
            ProposeCommandsHandler(approvalGate),
        )
        return McpServer(handlers)
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