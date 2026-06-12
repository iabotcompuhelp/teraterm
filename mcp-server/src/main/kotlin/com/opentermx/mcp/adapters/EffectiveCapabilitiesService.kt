package com.opentermx.mcp.adapters

import com.opentermx.mcp.telemetry.TelemetryStore
import com.opentermx.mgmt.AdapterRegistry
import com.opentermx.mgmt.DeviceRef
import com.opentermx.mgmt.MgmtMethod
import com.opentermx.netparsers.Vendor
import org.slf4j.LoggerFactory

/**
 * UN SOLO punto de verdad para "qué puede hacer el LLM con este equipo" (Fase 6C). Calcula
 * los métodos de gestión EFECTIVOS como la intersección de cuatro dimensiones:
 *
 *   soportado por el modelo (catálogo `default_methods`)
 *   ∩ habilitado por el operador en el dispositivo (`device_management_settings`)
 *   ∩ feature flag del adaptador encendido
 *   ∩ adaptador disponible en runtime (`AdapterRegistry`)
 *
 * Lo consumen `get_management_methods`, `get_device_profile`, el generador del MD de
 * gestión y los validadores de `adapter_read`. Cuatro consumidores, una implementación.
 *
 * Los métodos BASE (CLI_SSH/CLI_SERIAL) no requieren opt-in del operador: la CLI es el
 * transporte de la sesión. El opt-in trazable (error #55) aplica a Netmiko/Ansible/REST.
 */
class EffectiveCapabilitiesService(
    private val store: TelemetryStore,
    private val registry: AdapterRegistry,
    /** Feature flag por método (REST/Netmiko/Ansible nacen apagados; CLI siempre on). */
    private val flagEnabled: (MgmtMethod) -> Boolean = { it in MgmtMethod.BASELINE },
) {

    data class MethodStatus(
        val method: MgmtMethod,
        val supportedByCatalog: Boolean,
        val enabledOnDevice: Boolean,
        val flagEnabled: Boolean,
        val availableInRuntime: Boolean,
        val unavailableReason: String?,
        val readOperations: List<String>,
    ) {
        /** Efectivo = las cuatro dimensiones se cumplen. Es lo único que el LLM ve usable. */
        val effective: Boolean
            get() = supportedByCatalog && enabledOnDevice && flagEnabled && availableInRuntime
    }

    private val log = LoggerFactory.getLogger(javaClass)

    /** Estado por método para un device. Lista vacía si no hay BD o el device no existe. */
    fun statusFor(deviceId: Long): List<MethodStatus> {
        val db = store.db() ?: return emptyList()
        val device = db.devices.findById(deviceId) ?: return emptyList()
        val model = db.catalog.catalogModelOf(deviceId)
        val catalogMethods = model?.defaultMethods?.mapNotNull { MgmtMethod.fromCatalog(it) }?.toSet()
            ?: MgmtMethod.BASELINE // sin modelo de catálogo: solo CLI
        val enabledOnDevice = db.catalog.managementSettingsOf(deviceId)
            .filter { it["enabled"] == true }
            .mapNotNull { MgmtMethod.fromCatalog(it["method"].toString()) }
            .toSet()

        val ref = DeviceRef(
            deviceId = deviceId,
            hostname = device["hostname"]?.toString().orEmpty(),
            vendor = runCatching { Vendor.valueOf(device["vendor"].toString()) }.getOrDefault(Vendor.UNKNOWN),
            family = model?.family,
        )

        return MgmtMethod.entries.map { method ->
            val supported = method in catalogMethods
            // CLI base: habilitado por default (no exige opt-in); el resto, opt-in explícito.
            val enabled = method in MgmtMethod.BASELINE || method in enabledOnDevice
            val flag = flagEnabled(method)
            val availability = registry.availability(method)
            val readOps = registry.forMethod(method)
                ?.describe(ref)?.readOperations?.map { it.id }
                ?: emptyList()
            MethodStatus(
                method = method,
                supportedByCatalog = supported,
                enabledOnDevice = enabled,
                flagEnabled = flag,
                availableInRuntime = availability.isAvailable,
                unavailableReason = availability.reasonOrNull,
                readOperations = readOps,
            )
        }
    }

    /** Solo los métodos efectivos (atajo para consumidores que no quieren el detalle). */
    fun effectiveMethods(deviceId: Long): List<MgmtMethod> =
        statusFor(deviceId).filter { it.effective }.map { it.method }
}
