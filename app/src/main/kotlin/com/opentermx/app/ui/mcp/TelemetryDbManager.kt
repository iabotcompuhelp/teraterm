package com.opentermx.app.ui.mcp

import com.opentermx.app.settings.DatabaseSettings
import com.opentermx.common.crypto.EncryptedValue
import com.opentermx.common.crypto.SecretCipher
import com.opentermx.mcp.exec.SessionCommandRunner
import com.opentermx.mcp.telemetry.ImportLegacyAuditCsv
import com.opentermx.mcp.telemetry.MetricsPollScheduler
import com.opentermx.mcp.telemetry.TelemetryStore
import com.opentermx.telemetrydb.DbConfig
import com.opentermx.telemetrydb.TelemetryDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Singleton de la capa de telemetría PostgreSQL (Fase 3), simétrico a [McpServerManager]:
 * abre/cierra la conexión según settings, corre el import one-shot del audit CSV legacy
 * (idempotente por hash) cada vez que la BD pasa a disponible, y arranca/para el
 * scheduler de muestreo.
 *
 * El [SessionCommandRunner] es ÚNICO en el proceso ([sharedCommandRunner]) y lo comparten
 * el server MCP y el scheduler: el mutex por sesión solo serializa si todos los caminos
 * que inyectan comandos pasan por la misma instancia (error #29 del catálogo).
 */
object TelemetryDbManager {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Runner único del proceso — ver KDoc. McpServerManager lo reusa. */
    val sharedCommandRunner = SessionCommandRunner()

    @Volatile private var db: TelemetryDb? = null
    @Volatile private var appliedConfig: DbConfig? = null
    private var scheduler: MetricsPollScheduler? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Store vivo: los handlers lo capturan una vez y ven la conexión actual (o null). */
    val store = TelemetryStore { db }

    /**
     * Aplica settings: conecta/reconecta/desconecta y sincroniza el scheduler. La
     * conexión corre en IO — el hilo JavaFX jamás espera a la BD (error #19).
     */
    @Synchronized
    fun applySettings(settings: DatabaseSettings) {
        if (!settings.enabled) {
            stop()
            return
        }
        val config = DbConfig(
            host = settings.host,
            port = settings.port,
            database = settings.database,
            username = settings.username,
            password = resolvePassword(settings.password),
        )
        val needsReconnect = db == null || appliedConfig != config
        if (needsReconnect) {
            db?.close()
            db = null
            appliedConfig = config
            scope.launch {
                TelemetryDb.connect(config)
                    .onSuccess { connected ->
                        synchronized(this@TelemetryDbManager) { db = connected }
                        // One-shot idempotente: re-correrlo es un no-op por legacy_row_hash.
                        runCatching {
                            ImportLegacyAuditCsv.run(com.opentermx.ai.audit.AiAuditLog(), connected)
                        }.onFailure { log.warn("Import de audit legacy falló: {}", it.message) }
                    }
                    .onFailure {
                        // connect ya logueó el warning; liberar appliedConfig para que el
                        // próximo applySettings (mismos datos) reintente la conexión.
                        synchronized(this@TelemetryDbManager) {
                            if (appliedConfig == config) appliedConfig = null
                        }
                    }
            }
        }
        syncScheduler(settings)
    }

    @Synchronized
    fun stop() {
        scheduler?.stop()
        scheduler = null
        db?.close()
        db = null
        appliedConfig = null
    }

    private fun syncScheduler(settings: DatabaseSettings) {
        if (settings.schedulerEnabled) {
            if (scheduler == null) {
                scheduler = MetricsPollScheduler(
                    store = store,
                    runner = sharedCommandRunner,
                    pollIntervalMinutes = { settings.pollIntervalMinutes },
                    retentionDays = { settings.retentionDays },
                ).also { it.start(scope) }
            }
        } else {
            scheduler?.stop()
            scheduler = null
        }
    }

    /** Campo cifrado de settings, o env `OPENTERMX_DB_PASSWORD`, o vacío. Nunca plaintext en disco. */
    private fun resolvePassword(encrypted: EncryptedValue?): String {
        if (encrypted != null && !EncryptedValue.isEmpty(encrypted)) {
            runCatching { return SecretCipher.decrypt(encrypted) }
                .onFailure { log.warn("No se pudo descifrar el password de BD: {}", it.message) }
        }
        return System.getenv("OPENTERMX_DB_PASSWORD").orEmpty()
    }
}
