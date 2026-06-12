package com.opentermx.mcp.handlers

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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * `adapter_read` (6C.2): la validación server-side read-only (error #56) y la puerta de
 * la intersección. Un FakeRestAdapter expone una op READ y una WRITE; el handler deja
 * pasar la READ y rechaza la WRITE, las ops inexistentes, y los métodos no efectivos.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdapterReadHandlerTest {

    private val store = TelemetryStore { db }
    private val views = DeviceProfileViews(store, ReadOnlyCommandValidator.embedded())

    private val executed = mutableListOf<String>()

    private inner class FakeRestAdapter : ManagementAdapter {
        override val method = MgmtMethod.REST_API
        override fun isAvailable() = AdapterAvailability.Available
        override fun describe(device: DeviceRef) = AdapterDescriptor(
            MgmtMethod.REST_API,
            listOf(
                OperationDescriptor("rest.get_ports", "GET ports", OperationKind.READ),
                OperationDescriptor("rest.set_vlan", "POST vlan", OperationKind.WRITE),
            ),
        )
        override suspend fun executeRead(device: DeviceRef, op: ReadOperation): AdapterResult {
            executed += op.id
            return AdapterResult.Success(mapOf("ports" to 48))
        }
        override suspend fun proposeWrite(device: DeviceRef, op: WriteOperation) =
            ProposalTicket(method, op.id, device.hostname, "p", op.rationale)
    }

    private fun handler(restEnabled: Boolean = true) = AdapterReadHandler(
        store, views,
        EffectiveCapabilitiesService(
            store = store,
            registry = AdapterRegistry(listOf(FakeRestAdapter())),
            flagEnabled = { it in MgmtMethod.BASELINE || (it == MgmtMethod.REST_API && restEnabled) },
        ),
        AdapterRegistry(listOf(FakeRestAdapter())),
    )

    @BeforeAll
    fun seed() {
        CatalogPackImporter(db).importBuiltins()
    }

    /** Device 2930F con REST habilitado por el operador. */
    private fun device(ip: String, restEnabled: Boolean = true): String {
        val deviceId = db.devices.upsert("sw-$ip", ip, 22, "SSH", Vendor.ARUBA_PROVISION)!!
        db.catalog.assignCatalogModel(deviceId, db.catalog.listModels().first { it.name == "2930F" }.id)
        if (restEnabled) db.catalog.setManagementMethod(deviceId, "REST_API", enabled = true, enabledBy = "op")
        return "sw-$ip"
    }

    @Test
    fun `operacion de lectura efectiva se ejecuta`() = runBlocking {
        val host = device("10.99.9.1")
        val r = handler().invoke(mapOf("deviceHostname" to host, "method" to "REST_API", "operation" to "rest.get_ports"))
        assertEquals(true, r["ok"])
        assertEquals("external_device", r["contentOrigin"])
        assertTrue(executed.contains("rest.get_ports"))
    }

    @Test
    fun `operacion de ESCRITURA se rechaza server-side (error 56)`() = runBlocking {
        val host = device("10.99.9.2")
        executed.clear()
        val ex = assertThrows(McpToolException::class.java) {
            runBlocking { handler().invoke(mapOf("deviceHostname" to host, "method" to "REST_API", "operation" to "rest.set_vlan")) }
        }
        assertEquals(McpToolException.ErrorCode.INVALID_ARGUMENT, ex.code)
        assertTrue(ex.message!!.contains("no es de lectura"))
        assertFalse(executed.contains("rest.set_vlan"), "no se ejecutó la escritura")
    }

    @Test
    fun `operacion inexistente se rechaza`() {
        val host = device("10.99.9.3")
        val ex = assertThrows(McpToolException::class.java) {
            runBlocking { handler().invoke(mapOf("deviceHostname" to host, "method" to "REST_API", "operation" to "rest.nope")) }
        }
        assertTrue(ex.message!!.contains("no existe"))
    }

    @Test
    fun `metodo no efectivo se rechaza con el motivo`() {
        // REST soportado por catálogo + habilitado, pero flag apagado → no efectivo.
        val host = device("10.99.9.4")
        val ex = assertThrows(McpToolException::class.java) {
            runBlocking { handler(restEnabled = false).invoke(mapOf("deviceHostname" to host, "method" to "REST_API", "operation" to "rest.get_ports")) }
        }
        assertTrue(ex.message!!.contains("no efectivo"))
        assertTrue(ex.message!!.contains("flag"))
    }

    @Test
    fun `device no inventariado es NOT_FOUND`() {
        val ex = assertThrows(McpToolException::class.java) {
            runBlocking { handler().invoke(mapOf("deviceHostname" to "no-existe", "method" to "REST_API", "operation" to "rest.get_ports")) }
        }
        assertEquals(McpToolException.ErrorCode.NOT_FOUND, ex.code)
    }

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
