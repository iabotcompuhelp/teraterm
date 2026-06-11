package com.opentermx.app.ui.mcp

import com.opentermx.app.settings.AppSettings
import com.opentermx.app.ui.ai.KnowledgeBaseHolder
import com.opentermx.mcp.fingerprint.AutoFingerprint
import com.opentermx.mcp.fingerprint.DeviceProfileViews
import com.opentermx.mcp.fingerprint.FingerprintPersister
import com.opentermx.mcp.fingerprint.FingerprintService
import com.opentermx.mcp.fingerprint.RagDocGenerator
import com.opentermx.mcp.fingerprint.RoleRules
import com.opentermx.mcp.security.ReadOnlyCommandValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory

/**
 * Singleton del auto-fingerprint al conectar (Fase 5, error #38), simétrico a
 * [TelemetryDbManager]: vive INDEPENDIENTE del server MCP — el perfil de dispositivos
 * es una capacidad de telemetría, no del transporte MCP.
 *
 * El gate real es la BD: [AutoFingerprint] se saltea sin PostgreSQL, así que registrar
 * el listener siempre es barato. Los settings se leen en vivo (lambdas): togglear
 * `fingerprint.autoOnConnect` o cambiar `ttlDays` aplica sin reiniciar.
 */
object AutoFingerprintManager {

    private val log = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var subscription: AutoCloseable? = null

    /**
     * Registra (o RE-registra) el listener. Idempotente pero reconstruye: `enabled` y
     * `ttlDays` son lambdas que ya ven settings vivos, pero `dryRun`/`activeProbing`
     * quedan fijos en el [FingerprintService] — al guardar el diálogo de Setup se llama
     * de nuevo y el servicio se rearma con los valores nuevos.
     */
    @Synchronized
    fun applySettings(appSettings: () -> AppSettings) {
        runCatching { subscription?.close() }
        subscription = null
        val store = TelemetryDbManager.store
        val validator = ReadOnlyCommandValidator.default()
        val views = DeviceProfileViews(store, validator)
        val service = FingerprintService(
            runner = TelemetryDbManager.sharedCommandRunner,
            validator = validator,
            roleRules = RoleRules.default(),
            dryRun = appSettings().fingerprint.dryRun,
            activeProbing = appSettings().fingerprint.activeProbing,
        )
        val ragDocs = RagDocGenerator(
            store = store,
            views = views,
            kbProvider = { KnowledgeBaseHolder.get(appSettings().aiAssistant) },
        )
        val auto = AutoFingerprint(
            service = service,
            store = store,
            persister = FingerprintPersister(store, views, ragDocs),
            scope = scope,
            enabled = { appSettings().fingerprint.autoOnConnect },
            ttlDays = { appSettings().fingerprint.ttlDays },
        )
        subscription = auto.start()
        log.info(
            "Auto-fingerprint registrado (autoOnConnect={}, ttlDays={}, dryRun={})",
            appSettings().fingerprint.autoOnConnect,
            appSettings().fingerprint.ttlDays,
            appSettings().fingerprint.dryRun,
        )
    }

    @Synchronized
    fun stop() {
        runCatching { subscription?.close() }
        subscription = null
    }
}
