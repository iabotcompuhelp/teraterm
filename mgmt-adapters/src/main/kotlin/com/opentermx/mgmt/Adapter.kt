package com.opentermx.mgmt

import com.opentermx.netparsers.Vendor

/**
 * Métodos de gestión soportados (Fase 6C). Alineado 1:1 con el enum `mgmt_method_t` de
 * PostgreSQL (V3_1) y con `CatalogRepository.MGMT_METHODS`.
 */
enum class MgmtMethod {
    CLI_SSH, CLI_SERIAL, NETMIKO, ANSIBLE, REST_API, SNMP;

    companion object {
        /** Métodos que NO requieren un adaptador externo: la CLI es el transporte base. */
        val BASELINE = setOf(CLI_SSH, CLI_SERIAL)

        fun fromCatalog(name: String): MgmtMethod? =
            runCatching { valueOf(name) }.getOrNull()
    }
}

/**
 * Referencia a un dispositivo para operaciones de adaptador. No lleva credenciales: las
 * inyecta la capa de ejecución desde el keystore al usarlas (invariante: el LLM nunca ve
 * credenciales). [sessionId] está presente solo si hay una sesión interactiva abierta
 * (necesaria para el adaptador CLI).
 */
data class DeviceRef(
    val deviceId: Long,
    val hostname: String,
    val vendor: Vendor,
    val family: String? = null,
    val sessionId: String? = null,
)

/** ¿El runtime necesario para este método existe en esta máquina? */
sealed interface AdapterAvailability {
    data object Available : AdapterAvailability
    /** No disponible, con el motivo EXACTO y accionable (error #63: nada desaparece en silencio). */
    data class Unavailable(val reason: String) : AdapterAvailability

    val isAvailable: Boolean get() = this is Available
    val reasonOrNull: String? get() = (this as? Unavailable)?.reason
}

/** Clasificación read/write de una operación — la deriva el validador server-side (error #56). */
enum class OperationKind { READ, WRITE }

/**
 * Descriptor de una operación que un adaptador expone para un device. El validador del
 * lado servidor se DERIVA de esto (no se escribe a mano por adaptador): `adapter_read`
 * solo acepta operaciones [OperationKind.READ].
 */
data class OperationDescriptor(
    val id: String,                 // ej. "rest.get_interfaces", "cli.run_readonly"
    val description: String,
    val kind: OperationKind,
    val timeoutSeconds: Int = 30,   // por operación (error #61: un playbook ≠ un GET)
)

/** Qué expone un adaptador para un device concreto. */
data class AdapterDescriptor(
    val method: MgmtMethod,
    val operations: List<OperationDescriptor>,
) {
    val readOperations: List<OperationDescriptor> get() = operations.filter { it.kind == OperationKind.READ }
    val writeOperations: List<OperationDescriptor> get() = operations.filter { it.kind == OperationKind.WRITE }
    fun operation(id: String): OperationDescriptor? = operations.firstOrNull { it.id == id }
}

/** Operación de lectura a ejecutar (sin aprobación). */
data class ReadOperation(val id: String, val params: Map<String, Any?> = emptyMap())

/** Operación de escritura a PROPONER (jamás se ejecuta sin pasar por el ApprovalGate). */
data class WriteOperation(val id: String, val payload: Map<String, Any?>, val rationale: String)

/** Resultado de una lectura. `sealed`: el compilador obliga a manejar el fallo. */
sealed interface AdapterResult {
    data class Success(val data: Map<String, Any?>) : AdapterResult
    data class Failure(val reason: String) : AdapterResult
}

/**
 * Ticket de una escritura PROPUESTA (no ejecutada). Viaja al ApprovalGate, que muestra el
 * [literalPayload] al operador; solo tras la aprobación humana el adaptador ejecuta.
 * La implementación completa llega en 6C.3 — acá queda el tipo del contrato.
 */
data class ProposalTicket(
    val method: MgmtMethod,
    val operationId: String,
    val deviceHostname: String,
    /** Contenido EXACTO que verá el operador (playbook/payload/comandos). No un resumen. */
    val literalPayload: String,
    val rationale: String,
)

/**
 * Contrato común de todo adaptador de gestión (Fase 6C). Lectura y escritura separadas a
 * propósito: [executeRead] corre sin aprobación (equivalente a la whitelist read-only de
 * la Fase 1); [proposeWrite] SIEMPRE produce un ticket para el ApprovalGate — nunca
 * ejecuta. Solo el gate, tras aprobación humana, invoca el execute interno del adaptador.
 */
interface ManagementAdapter {
    val method: MgmtMethod

    fun isAvailable(): AdapterAvailability

    fun describe(device: DeviceRef): AdapterDescriptor

    suspend fun executeRead(device: DeviceRef, op: ReadOperation): AdapterResult

    suspend fun proposeWrite(device: DeviceRef, op: WriteOperation): ProposalTicket

    /**
     * Aplica EFECTIVAMENTE un cambio de configuración que ya fue aprobado por un operador
     * humano (Fase 6C.3). Es la contraparte ejecutora de [proposeWrite].
     *
     * INVARIANTE DE SEGURIDAD (no negociable): este método sólo puede invocarse DESPUÉS de
     * que el [com.opentermx.mcp.security.ApprovalGate] devolvió `Approve` sobre el ticket
     * de [proposeWrite]. Ninguna tool MCP ni ruta directa lo llama: el único caller legítimo
     * es la rama post-aprobación del handler `propose_adapter_write`. La separación es a
     * propósito — `proposeWrite` JAMÁS toca el equipo; `applyApprovedChange` SÍ, y por eso
     * vive detrás de la revisión humana.
     *
     * El default devuelve [AdapterResult.Failure]: un adaptador que no implementa escrituras
     * (o todavía no las habilita) nunca aplica nada por accidente (fail-closed).
     */
    suspend fun applyApprovedChange(device: DeviceRef, op: WriteOperation): AdapterResult =
        AdapterResult.Failure("el adaptador `$method` no implementa la aplicación de cambios aprobados")
}
