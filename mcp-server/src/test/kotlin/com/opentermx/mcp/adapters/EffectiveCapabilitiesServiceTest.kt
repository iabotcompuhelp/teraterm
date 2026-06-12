package com.opentermx.mcp.adapters

import com.opentermx.mcp.exec.SessionCommandRunner
import com.opentermx.mcp.telemetry.TelemetryStore
import com.opentermx.mgmt.AdapterRegistry
import com.opentermx.mgmt.MgmtMethod
import com.opentermx.netparsers.Vendor
import com.opentermx.telemetrydb.CatalogPackImporter
import com.opentermx.telemetrydb.DbConfig
import com.opentermx.telemetrydb.TelemetryDb
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Intersección de capacidades (Fase 6C.1): efectivo = modelo (catálogo) ∩ dispositivo
 * (opt-in) ∩ flag ∩ runtime. Con un solo adaptador real (CLI), el 2930F del pack soporta
 * REST/Netmiko/Ansible por catálogo, pero NINGUNO es efectivo hasta que se den las cuatro
 * dimensiones — y CLI sí, por ser el método base.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EffectiveCapabilitiesServiceTest {

    private val store = TelemetryStore { db }
    private val runner = SessionCommandRunner(pollIntervalMillis = 15, rawSender = { null })

    // Registry con solo CLI (como en runtime hoy). Flags: REST on, el resto off.
    private fun service(restFlag: Boolean = false, withRestAdapter: Boolean = false) =
        EffectiveCapabilitiesService(
            store = store,
            registry = AdapterRegistry(
                buildList {
                    add(CliSshAdapter(runner))
                    if (withRestAdapter) add(FakeRestAdapter())
                },
            ),
            flagEnabled = { m ->
                when (m) {
                    MgmtMethod.CLI_SSH, MgmtMethod.CLI_SERIAL -> true
                    MgmtMethod.REST_API -> restFlag
                    else -> false
                }
            },
        )

    @BeforeAll
    fun seed() {
        CatalogPackImporter(db).importBuiltins()
    }

    private fun device2930f(ip: String): Long {
        val deviceId = db.devices.upsert("sw-$ip", ip, 22, "SSH", Vendor.ARUBA_PROVISION)!!
        val model = db.catalog.listModels().first { it.name == "2930F" }
        db.catalog.assignCatalogModel(deviceId, model.id)
        return deviceId
    }

    @Test
    fun `CLI es efectivo por ser metodo base, REST soportado por catalogo pero no efectivo`() {
        val deviceId = device2930f("10.98.0.1")
        val statuses = service().statusFor(deviceId).associateBy { it.method }

        val cli = statuses.getValue(MgmtMethod.CLI_SSH)
        assertTrue(cli.effective, "CLI es el transporte base: efectivo")
        assertTrue(cli.supportedByCatalog && cli.enabledOnDevice && cli.availableInRuntime)
        assertTrue(cli.readOperations.contains("cli.run_readonly_command"))

        val rest = statuses.getValue(MgmtMethod.REST_API)
        assertTrue(rest.supportedByCatalog, "el 2930F declara REST en defaultMethods")
        assertFalse(rest.enabledOnDevice, "no se habilitó por dispositivo (opt-in)")
        assertFalse(rest.flagEnabled, "flag REST apagado")
        assertFalse(rest.availableInRuntime, "sin adaptador REST registrado")
        assertFalse(rest.effective)
        assertTrue(rest.unavailableReason!!.contains("sin adaptador"))
    }

    @Test
    fun `REST se vuelve efectivo solo cuando las cuatro dimensiones se cumplen`() {
        val deviceId = device2930f("10.98.0.2")
        // 1) habilitar por dispositivo (opt-in del operador) + 2) flag + 3) adaptador en runtime.
        db.catalog.setManagementMethod(deviceId, "REST_API", enabled = true, enabledBy = "op")

        // Con opt-in pero sin flag ni adaptador → todavía no efectivo.
        assertFalse(service().statusFor(deviceId).first { it.method == MgmtMethod.REST_API }.effective)

        // Con opt-in + flag + adaptador registrado → efectivo.
        val s = service(restFlag = true, withRestAdapter = true)
            .statusFor(deviceId).first { it.method == MgmtMethod.REST_API }
        assertTrue(s.effective, "$s")
        assertTrue(s.readOperations.isNotEmpty())
    }

    @Test
    fun `device sin modelo de catalogo solo expone CLI`() {
        val deviceId = db.devices.upsert("sw-nocatalog", "10.98.0.3", 22, "SSH", Vendor.CISCO_IOS)!!
        val effective = service().effectiveMethods(deviceId)
        assertEquals(listOf(MgmtMethod.CLI_SSH), effective.filter { it == MgmtMethod.CLI_SSH })
        assertFalse(MgmtMethod.NETMIKO in effective)
    }

    /** Adaptador REST de mentira para el test de runtime (la impl real llega en 6C.2). */
    private class FakeRestAdapter : com.opentermx.mgmt.ManagementAdapter {
        override val method = MgmtMethod.REST_API
        override fun isAvailable() = com.opentermx.mgmt.AdapterAvailability.Available
        override fun describe(device: com.opentermx.mgmt.DeviceRef) = com.opentermx.mgmt.AdapterDescriptor(
            method,
            listOf(com.opentermx.mgmt.OperationDescriptor("rest.get_system", "d", com.opentermx.mgmt.OperationKind.READ)),
        )
        override suspend fun executeRead(device: com.opentermx.mgmt.DeviceRef, op: com.opentermx.mgmt.ReadOperation) =
            com.opentermx.mgmt.AdapterResult.Success(emptyMap())
        override suspend fun proposeWrite(device: com.opentermx.mgmt.DeviceRef, op: com.opentermx.mgmt.WriteOperation) =
            com.opentermx.mgmt.ProposalTicket(method, op.id, device.hostname, "p", op.rationale)
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
