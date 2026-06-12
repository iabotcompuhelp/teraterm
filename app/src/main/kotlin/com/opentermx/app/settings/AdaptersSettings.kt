package com.opentermx.app.settings

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Feature flags de los adaptadores de gestión no-CLI (Fase 6C). Nacen APAGADOS: habilitar
 * Netmiko/Ansible/REST es opt-in explícito (son superficie de ataque nueva). CLI/SSH no
 * tiene flag — es el transporte base de la sesión.
 *
 * Esto es el flag GLOBAL del tipo de adaptador; la habilitación POR DISPOSITIVO vive en
 * `device_management_settings` (catálogo, Fase 6A). Un método es efectivo solo si ambos
 * (flag global + opt-in del device) más el modelo y el runtime lo permiten.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AdaptersSettings(
    val restEnabled: Boolean = false,
    val netmikoEnabled: Boolean = false,
    val ansibleEnabled: Boolean = false,
)
