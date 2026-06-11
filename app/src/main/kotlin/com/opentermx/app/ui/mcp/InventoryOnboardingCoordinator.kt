package com.opentermx.app.ui.mcp

import com.opentermx.app.settings.AppSettings
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.fingerprint.FingerprintService
import com.opentermx.mcp.onboarding.OnboardingService
import com.opentermx.mcp.telemetry.TelemetryStore
import javafx.application.Platform
import org.slf4j.LoggerFactory

/**
 * Puente entre el auto-fingerprint de fondo (Fase 5/6B) y la UI de onboarding: cuando
 * un equipo no inventariado se sondea, [offer] decide si corresponde ofrecer el alta y,
 * de ser así, despierta el banner en el FX thread.
 *
 * Lo arma [AutoFingerprintManager] (tiene el store y los settings); [MainWindow] registra
 * el [listener] que muestra el banner. Desacoplado para que el módulo MCP no dependa de
 * JavaFX y el banner no dependa del scheduler.
 */
object InventoryOnboardingCoordinator {

    /** Datos auto-contenidos que viajan al banner/asistente (en el FX thread). */
    data class Candidate(
        val sessionId: SessionId,
        val host: String?,
        val report: FingerprintService.FingerprintReport,
    )

    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var appSettings: (() -> AppSettings)? = null
    @Volatile var service: OnboardingService? = null
        private set

    /** Lo setea MainWindow: muestra el banner sobre la pestaña de la sesión. */
    @Volatile var listener: ((Candidate) -> Unit)? = null

    fun configure(appSettings: () -> AppSettings, store: TelemetryStore) {
        this.appSettings = appSettings
        this.service = OnboardingService(store)
    }

    /** ¿El onboarding al conectar está activo? Lo consulta AutoFingerprintManager. */
    fun isEnabled(): Boolean = appSettings?.invoke()?.onboarding?.askOnConnect == true

    /**
     * Invocado por el auto-fingerprint (thread IO) con el reporte de un equipo no
     * inventariado. Filtra los hosts en "no volver a preguntar" y, si corresponde,
     * notifica a la UI en el FX thread.
     */
    fun offer(sessionId: SessionId, report: FingerprintService.FingerprintReport) {
        val settings = appSettings?.invoke()?.onboarding ?: return
        if (!settings.askOnConnect) return
        val host = com.opentermx.common.ai.SessionRegistry.metadataOf(sessionId)?.host
        if (host != null && settings.ignoredHosts.any { it.equals(host, ignoreCase = true) }) {
            log.debug("onboarding: host `{}` en 'no volver a preguntar' — sin banner", host)
            return
        }
        val cb = listener ?: return
        Platform.runLater {
            runCatching { cb(Candidate(sessionId, host, report)) }
                .onFailure { log.warn("onboarding banner de {} falló: {}", sessionId.value, it.message) }
        }
    }
}
