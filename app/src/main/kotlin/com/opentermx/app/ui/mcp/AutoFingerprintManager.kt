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
import kotlinx.coroutines.launch
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
    @Volatile private var profileViews: DeviceProfileViews? = null
    @Volatile private var ragDocsRef: RagDocGenerator? = null

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
        profileViews = views
        ragDocsRef = ragDocs
        log.info(
            "Auto-fingerprint registrado (autoOnConnect={}, ttlDays={}, dryRun={})",
            appSettings().fingerprint.autoOnConnect,
            appSettings().fingerprint.ttlDays,
            appSettings().fingerprint.dryRun,
        )
    }

    /**
     * El operador editó/confirmó un perfil (diálogo de Setup): invalida la caché de
     * enriquecimiento (por hostname y por IP de management) y regenera el doc RAG en
     * IO. No-op si el manager nunca se registró (p. ej. modo terminal).
     */
    fun notifyProfileEdited(hostname: String, mgmtAddress: String? = null) {
        profileViews?.invalidate(hostname)
        mgmtAddress?.let { profileViews?.invalidate(it) }
        val ragDocs = ragDocsRef ?: return
        scope.launch {
            runCatching { ragDocs.regenerateFor(hostname) }
                .onFailure { log.warn("regeneración RAG de `{}` falló: {}", hostname, it.message) }
        }
    }

    /**
     * El operador BORRÓ un device del inventario (error #49): borra su doc RAG del
     * disco y del índice Lucene, e invalida la caché de enriquecimiento. Si el manager
     * nunca se registró, la pasada de huérfanos de regenerateAll lo limpia igual.
     */
    fun notifyDeviceRemoved(hostname: String, mgmtAddress: String? = null) {
        profileViews?.invalidate(hostname)
        mgmtAddress?.let { profileViews?.invalidate(it) }
        val ragDocs = ragDocsRef ?: return
        scope.launch {
            runCatching { ragDocs.removeFor(hostname) }
                .onFailure { log.warn("borrado del doc RAG de `{}` falló: {}", hostname, it.message) }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { subscription?.close() }
        subscription = null
    }
}
