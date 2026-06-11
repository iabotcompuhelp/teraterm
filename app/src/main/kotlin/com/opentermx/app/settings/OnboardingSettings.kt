package com.opentermx.app.settings

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Onboarding de equipos al conectar (Fase 6B). Cuando está activo, conectarse a un
 * equipo SSH/serial que no está en el inventario ofrece agregarlo (banner no bloqueante
 * + asistente pre-llenado por el fingerprint). Solo actúa con la base de telemetría
 * habilitada — sin PostgreSQL no hay inventario que consultar.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class OnboardingSettings(
    val askOnConnect: Boolean = true,
    /**
     * Hosts para los que el operador eligió "No volver a preguntar". El banner no se
     * muestra para estos; el equipo se puede agregar después desde el catálogo/perfiles.
     */
    val ignoredHosts: List<String> = emptyList(),
)
