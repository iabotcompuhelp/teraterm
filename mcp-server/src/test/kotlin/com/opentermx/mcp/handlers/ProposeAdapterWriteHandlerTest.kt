package com.opentermx.mcp.handlers

import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.mcp.adapters.EffectiveCapabilitiesService
import com.opentermx.mcp.fingerprint.DeviceProfileViews
import com.opentermx.mcp.security.ReadOnlyCommandValidator
import com.opentermx.mcp.telemetry.TelemetryStore
import com.opentermx.mgmt.AdapterAvailability
import com.opentermx.mgmt.AdapterDescriptor
import com.opentermx.mgmt.AdapterRegistry
import com.opentermx.mgmt.AdapterResult
import com.opentermx.mgmt.DeviceRef
import com.opentermx.mgmt.ManagementAdapter
import com.opentermx.mgmt.MgmtMethod
import com.opentermx.mgmt.OperationDescriptor
import com.opentermx.mgmt.OperationKind
import com.opentermx.mgmt.ProposalTicket
import com.opentermx.mgmt.ReadOperation
import com.opentermx.mgmt.WriteOperation
import com.opentermx.netparsers.Vendor
import com.opentermx.telemetrydb.CatalogPackImporter
import com.opentermx.telemetrydb.DbConfig
import com.opentermx.telemetrydb.TelemetryDb
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * `propose_adapter_write` (6C.3): el camino de PROPUESTA y APROBACIÓN. El test más
 * importante es el negativo — con un gate que NO aprueba, `applyApprovedChange` jamás se
 * invoca (la regla central: ningún cambio se aplica sin aprobación humana explícita).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProposeAdapterWriteHandlerTest {

    private val store = TelemetryStore { db }
    private val views = DeviceProfileViews(store, ReadOnlyCommandValidator.embedded())

    /** Adaptador fake que registra cada invocación de applyApprovedChange (debe ser cero salvo en el approve). */
    private inner class RecordingAdapter : ManagementAdapter {
        val applyCalls = mutableListOf<WriteOperation>()
        var applyResult: AdapterResult = AdapterResult.Success(mapOf("changed" to true))

        override val method = MgmtMethod.REST_API
        override fun isAvailable() = AdapterAvailability.Available
        override fun describe(device: DeviceRef) = AdapterDescriptor(
            MgmtMethod.REST_API,
            listOf(
                OperationDescriptor("rest.get_ports", "GET ports", OperationKind.READ),
                OperationDescriptor("rest.set_vlan", "POST vlan", OperationKind.WRITE),
            ),
        )
        override suspend fun executeRead(device: DeviceRef, op: ReadOperation) =
            AdapterResult.Success(mapOf("ports" to 48))
        override suspend fun proposeWrite(device: DeviceRef, op: WriteOperation) = ProposalTicket(
            method = method,
            operationId = op.id,
            deviceHostname = device.hostname,
            literalPayload = "{\n  \"vlan\" : 30\n}",
            rationale = op.rationale,
        )
        override suspend fun applyApprovedChange(device: DeviceRef, op: WriteOperation): AdapterResult {
            applyCalls += op
            return applyResult
        }
    }

    private fun handler(
        adapter: RecordingAdapter,
        gate: FakeApprovalGate,
        writeEnabled: Boolean = true,
        auditLog: AiAuditLog = AiAuditLog(tempAuditFile()),
    ): ProposeAdapterWriteHandler {
        val registry = AdapterRegistry(listOf(adapter))
        return ProposeAdapterWriteHandler(
            store = store,
            views = views,
            capabilities = EffectiveCapabilitiesService(
                store = store,
                registry = registry,
                flagEnabled = { it in MgmtMethod.BASELINE || it == MgmtMethod.REST_API },
            ),
            registry = registry,
            approvalGate = gate,
            writeEnabled = { writeEnabled },
            auditLog = auditLog,
        )
    }

    @BeforeAll
    fun seed() {
        CatalogPackImporter(db).importBuiltins()
    }

    /** Device 2930F con REST habilitado por el operador. */
    private fun device(ip: String): String {
        val deviceId = db.devices.upsert("sw-$ip", ip, 22, "SSH", Vendor.ARUBA_PROVISION)!!
        db.catalog.assignCatalogModel(deviceId, db.catalog.listModels().first { it.name == "2930F" }.id)
        db.catalog.setManagementMethod(deviceId, "REST_API", enabled = true, enabledBy = "op")
        return "sw-$ip"
    }

    private fun args(host: String, operation: String = "rest.set_vlan") = mapOf(
        "deviceHostname" to host,
        "method" to "REST_API",
        "operation" to operation,
        "rationale" to "crear vlan 30",
        "payload" to mapOf("vlan" to 30),
    )

    @Test
    fun `con el flag en false responde deshabilitado y no crea ticket`() = runBlocking {
        val host = device("10.88.1.1")
        val adapter = RecordingAdapter()
        val gate = FakeApprovalGate() // rechaza por default
        val r = handler(adapter, gate, writeEnabled = false).invoke(args(host))

        assertEquals(false, r["ok"])
        assertEquals("disabled", r["status"])
        assertEquals(null, r["ticketId"])
        assertTrue(gate.invocations.isEmpty(), "el gate no debe invocarse con el flag apagado")
        assertTrue(adapter.applyCalls.isEmpty(), "no se aplica nada con el flag apagado")
    }

    @Test
    fun `gate que no aprueba - applyApprovedChange NUNCA se invoca (test negativo central)`() = runBlocking {
        val host = device("10.88.1.2")
        val adapter = RecordingAdapter()
        val gate = FakeApprovalGate().also { it.rejectAll() }
        val r = handler(adapter, gate).invoke(args(host))

        assertEquals(false, r["ok"])
        assertEquals("rejected", r["status"])
        assertTrue(r["ticketId"] != null, "el ticket existe aunque se haya rechazado")
        assertEquals(1, gate.invocations.size, "el gate se invocó una vez")
        assertTrue(adapter.applyCalls.isEmpty(), "INVARIANTE: sin aprobación, jamás se aplica el cambio")
    }

    @Test
    fun `con aprobacion - applyApprovedChange se invoca exactamente una vez y se audita`() = runBlocking {
        val host = device("10.88.1.3")
        val adapter = RecordingAdapter()
        val gate = FakeApprovalGate().also { it.approveAll() }
        val auditFile = tempAuditFile()
        val r = handler(adapter, gate, auditLog = AiAuditLog(auditFile)).invoke(args(host))

        assertEquals(true, r["ok"])
        assertEquals("applied", r["status"])
        assertEquals(1, adapter.applyCalls.size, "applyApprovedChange se invoca exactamente una vez")
        assertEquals("rest.set_vlan", adapter.applyCalls.single().id)
        assertTrue(r["literalPayload"].toString().contains("vlan"), "el ticket lleva la representación legible")

        val audited = AiAuditLog(auditFile).read(sessionId = host)
        assertTrue(audited.isNotEmpty(), "el resultado se registra en auditoría")
        assertFalse(audited.first().rejected)
    }

    @Test
    fun `cuando la aplicacion aprobada falla, el estado es apply_failed`() = runBlocking {
        val host = device("10.88.1.4")
        val adapter = RecordingAdapter().also { it.applyResult = AdapterResult.Failure("equipo no respondió") }
        val gate = FakeApprovalGate().also { it.approveAll() }
        val r = handler(adapter, gate).invoke(args(host))

        assertEquals(false, r["ok"])
        assertEquals("apply_failed", r["status"])
        assertEquals(1, adapter.applyCalls.size, "se intentó aplicar una vez")
        assertTrue(r["error"].toString().contains("equipo no respondió"))
    }

    @Test
    fun `una operacion de lectura enviada a propose_adapter_write se rechaza`() {
        val host = device("10.88.1.5")
        val adapter = RecordingAdapter()
        val gate = FakeApprovalGate().also { it.approveAll() }
        val ex = assertThrows(McpToolException::class.java) {
            runBlocking { handler(adapter, gate).invoke(args(host, operation = "rest.get_ports")) }
        }
        assertEquals(McpToolException.ErrorCode.INVALID_ARGUMENT, ex.code)
        assertTrue(ex.message!!.contains("adapter_read"), "debe redirigir a adapter_read: ${ex.message}")
        assertTrue(adapter.applyCalls.isEmpty(), "una lectura nunca aplica un cambio")
        assertTrue(gate.invocations.isEmpty(), "ni siquiera llega al gate")
    }

    /** Path a un CSV de auditoría que AÚN NO existe (para que `append` escriba el header). */
    private fun tempAuditFile() =
        Files.createTempDirectory("audit").resolve("ai-audit.csv")

    private companion object {
        val db: TelemetryDb by lazy {
            val pg = EmbeddedPostgres.builder().start()
            Runtime.getRuntime().addShutdownHook(Thread { runCatching { pg.close() } })
            TelemetryDb.connect(
                DbConfig(host = "localhost", port = pg.port, database = "postgres", username = "postgres", password = "postgres"),
            ).getOrThrow()
        }
    }
}
