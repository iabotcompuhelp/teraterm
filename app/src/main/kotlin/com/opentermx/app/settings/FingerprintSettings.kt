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
    /**
     * Fingerprint automático en background al conectar una sesión (error #38). Solo
     * actúa con la BD de telemetría habilitada (sin PostgreSQL no hay caché TTL ni
     * perfil que actualizar — se saltea): por eso puede defaultear a `true` sin mandar
     * comandos sorpresa en instalaciones sin BD.
     */
    val autoOnConnect: Boolean = true,
    /**
     * TTL del fingerprint cacheado por dispositivo, en días. Dentro del TTL la conexión
     * no re-sondea, salvo que el hostname del prompt difiera del guardado o que el
     * operador lo fuerce con `refresh_device_fingerprint`.
     */
    val ttlDays: Int = 7,
)
