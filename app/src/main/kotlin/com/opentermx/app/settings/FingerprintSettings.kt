package com.opentermx.app.settings

/**
 * Fingerprinting de dispositivos (Fase 5). Como `database`/`monitoringIntegrations`,
 * por ahora se edita en `~/.opentermx/settings.json` (UI de settings pendiente).
 */
data class FingerprintSettings(
    /**
     * Con `true` el fingerprint ejecuta sondas y parsea pero NO persiste en la BD ni
     * regenera docs RAG — imprime el resultado en el reporte de la tool. Útil para
     * iterar contra equipos reales sin ensuciar la base.
     */
    val dryRun: Boolean = false,
    /**
     * Pruebas ACTIVAS de rol (`show wlan summary`, `show spanning-tree summary`) cuando
     * el patrón de modelo no resolvió. Opt-in: agrega comandos al equipo.
     */
    val activeProbing: Boolean = false,
)
