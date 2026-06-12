package com.opentermx.mcp.adapters

import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.exec.SessionCommandRunner
import com.opentermx.mcp.fingerprint.toAiVendor
import com.opentermx.mcp.security.ReadOnlyCommandValidator
import com.opentermx.mcp.security.ReadOnlyValidation
import com.opentermx.mgmt.AdapterAvailability
import com.opentermx.mgmt.AdapterDescriptor
import com.opentermx.mgmt.AdapterResult
import com.opentermx.mgmt.DeviceRef
import com.opentermx.mgmt.ManagementAdapter
import com.opentermx.mgmt.MgmtMethod
import com.opentermx.mgmt.OperationDescriptor
import com.opentermx.mgmt.OperationKind
import com.opentermx.mgmt.ProposalTicket
import com.opentermx.mgmt.ReadOperation
import com.opentermx.mgmt.WriteOperation

/**
 * Adaptador CLI/SSH (Fase 6C.1): envuelve el ejecutor read-only que ya existe
 * ([SessionCommandRunner] + [ReadOnlyCommandValidator]). Es el método BASE — siempre
 * disponible en proceso (no necesita runtime externo). Demuestra el contrato sin I/O nueva.
 *
 * Lectura: `cli.run_readonly_command` valida el comando contra la whitelist por
 * vendor/perfil y lo ejecuta. Escritura: `proposeWrite` produce un ticket para el
 * ApprovalGate (la ejecución real de cambios CLI sigue siendo `propose_commands`; el
 * ticket de adaptador se cablea en 6C.3).
 */
class CliSshAdapter(
    private val runner: SessionCommandRunner,
    private val validator: ReadOnlyCommandValidator = ReadOnlyCommandValidator.default(),
    private val readonlyProfileOf: (DeviceRef) -> String? = { null },
) : ManagementAdapter {

    override val method = MgmtMethod.CLI_SSH

    /** En proceso: siempre disponible. */
    override fun isAvailable(): AdapterAvailability = AdapterAvailability.Available

    override fun describe(device: DeviceRef): AdapterDescriptor = AdapterDescriptor(
        method = MgmtMethod.CLI_SSH,
        operations = listOf(
            OperationDescriptor(
                id = OP_RUN_READONLY,
                description = "Ejecuta un comando de SOLO LECTURA (whitelist por vendor/perfil) en la sesión activa.",
                kind = OperationKind.READ,
                timeoutSeconds = 30,
            ),
            OperationDescriptor(
                id = OP_CONFIG_WRITE,
                description = "Propone comandos de configuración (pasan por el ApprovalGate, no se ejecutan solos).",
                kind = OperationKind.WRITE,
                timeoutSeconds = 60,
            ),
        ),
    )

    override suspend fun executeRead(device: DeviceRef, op: ReadOperation): AdapterResult {
        if (op.id != OP_RUN_READONLY) {
            return AdapterResult.Failure("operación de lectura desconocida para CLI: `${op.id}`")
        }
        val command = (op.params["command"] as? String)?.trim()
            ?: return AdapterResult.Failure("falta `command`")
        val sessionId = device.sessionId
            ?: return AdapterResult.Failure("CLI requiere una sesión activa (sessionId)")
        val aiVendor = device.vendor.toAiVendor()
        // Validación server-side: solo lecturas whitelisteadas (defensa heredada de Fase 1).
        when (val v = validator.validate(command, aiVendor, profile = readonlyProfileOf(device))) {
            is ReadOnlyValidation.Rejected ->
                return AdapterResult.Failure("comando rechazado por la whitelist read-only: ${v.reason}")
            ReadOnlyValidation.Allowed -> Unit
        }
        if (SessionRegistry.sinkOf(SessionId(sessionId)) == null) {
            return AdapterResult.Failure("la sesión `$sessionId` no es inyectable")
        }
        return runCatching {
            val run = runner.run(SessionId(sessionId), aiVendor, command, READ_TIMEOUT_MILLIS)
            AdapterResult.Success(
                linkedMapOf(
                    "command" to command,
                    "output" to run.output,
                    "timedOut" to run.timedOut,
                )
            )
        }.getOrElse { AdapterResult.Failure(it.message ?: it.javaClass.simpleName) }
    }

    override suspend fun proposeWrite(device: DeviceRef, op: WriteOperation): ProposalTicket {
        val commands = (op.payload["commands"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        return ProposalTicket(
            method = MgmtMethod.CLI_SSH,
            operationId = op.id,
            deviceHostname = device.hostname,
            literalPayload = commands.joinToString("\n"),
            rationale = op.rationale,
        )
    }

    companion object {
        const val OP_RUN_READONLY = "cli.run_readonly_command"
        const val OP_CONFIG_WRITE = "cli.config_write"
        private const val READ_TIMEOUT_MILLIS = 30_000L
    }
}
