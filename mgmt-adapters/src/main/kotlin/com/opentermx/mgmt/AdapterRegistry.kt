package com.opentermx.mgmt

/**
 * Registro de adaptadores de gestión disponibles en el proceso (Fase 6C). Resuelve
 * adaptador por método y consulta su disponibilidad de runtime. Un método sin adaptador
 * registrado simplemente no está disponible (no hay impl que lo provea).
 */
class AdapterRegistry(adapters: List<ManagementAdapter> = emptyList()) {

    private val byMethod: Map<MgmtMethod, ManagementAdapter> = adapters.associateBy { it.method }

    fun forMethod(method: MgmtMethod): ManagementAdapter? = byMethod[method]

    fun all(): List<ManagementAdapter> = byMethod.values.toList()

    /**
     * Disponibilidad de runtime de [method]: el adaptador existe y su `isAvailable()` da
     * Available. Sin adaptador registrado → Unavailable con motivo accionable (error #63).
     */
    fun availability(method: MgmtMethod): AdapterAvailability =
        byMethod[method]?.isAvailable()
            ?: AdapterAvailability.Unavailable("sin adaptador `$method` registrado en este proceso")
}
