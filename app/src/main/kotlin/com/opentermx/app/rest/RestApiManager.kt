package com.opentermx.app.rest

import com.opentermx.app.settings.RestApiPersistedSettings
import com.opentermx.rest.RestApiConfig
import com.opentermx.rest.RestApiHooks
import com.opentermx.rest.RestApiLog
import com.opentermx.rest.RestApiServer
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.property.ReadOnlyIntegerProperty
import javafx.beans.property.ReadOnlyIntegerWrapper
import javafx.beans.property.ReadOnlyStringProperty
import javafx.beans.property.ReadOnlyStringWrapper
import org.slf4j.LoggerFactory

/**
 * Owns el ciclo de vida del [RestApiServer]. Singleton para que la status bar y el
 * diálogo "Setup → REST API" reflejen el mismo estado vivo.
 *
 * `start(...)` arranca el server si está habilitado en settings y todavía no corre;
 * `stop()` lo apaga. `applySettings(...)` reinicia si la config cambió.
 */
object RestApiManager {

    private val log = LoggerFactory.getLogger(javaClass)
    private val runningWrapper = ReadOnlyBooleanWrapper(false)
    private val portWrapper = ReadOnlyIntegerWrapper(-1)
    private val tokenWrapper = ReadOnlyStringWrapper("")

    val runningProperty: ReadOnlyBooleanProperty get() = runningWrapper.readOnlyProperty
    val portProperty: ReadOnlyIntegerProperty get() = portWrapper.readOnlyProperty
    val tokenProperty: ReadOnlyStringProperty get() = tokenWrapper.readOnlyProperty

    val isRunning: Boolean get() = server != null
    val activePort: Int get() = server?.effectivePort ?: -1

    private var server: RestApiServer? = null
    private var currentConfig: RestApiPersistedSettings? = null
    private var hooks: RestApiHooks? = null

    fun configure(hooks: RestApiHooks) {
        this.hooks = hooks
    }

    @Synchronized
    fun applySettings(settings: RestApiPersistedSettings): Result<Int?> {
        if (!settings.enabled) {
            stop()
            return Result.success(null)
        }
        if (isRunning && currentConfig == settings) {
            return Result.success(activePort)
        }
        stop()
        val hooksRef = hooks ?: return Result.failure(IllegalStateException("RestApiManager.configure(hooks) no llamado"))
        val auditLog = if (settings.apiLogPath.isBlank()) RestApiLog(null) else RestApiLog(Path.of(settings.apiLogPath))
        val srv = RestApiServer(
            hooks = hooksRef,
            config = RestApiConfig(
                bindHost = settings.bindHost,
                port = settings.port,
                token = settings.token,
                requireAuth = settings.requireAuth,
            ),
            auditLog = auditLog,
        )
        return runCatching {
            srv.start()
            server = srv
            currentConfig = settings
            val resolvedPort = srv.effectivePort ?: settings.port
            runningWrapper.set(true)
            portWrapper.set(resolvedPort)
            tokenWrapper.set(settings.token)
            log.info("REST API arrancado en {}:{}", settings.bindHost, resolvedPort)
            resolvedPort
        }.onFailure { e ->
            log.warn("No se pudo arrancar la REST API", e)
            server = null
            currentConfig = null
            runningWrapper.set(false)
            portWrapper.set(-1)
        }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        currentConfig = null
        runningWrapper.set(false)
        portWrapper.set(-1)
    }

    /**
     * Genera un token URL-safe de 32 bytes (43 chars Base64 sin padding). Usado por el
     * diálogo "Regenerar token" y al habilitar la REST API por primera vez.
     */
    fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
