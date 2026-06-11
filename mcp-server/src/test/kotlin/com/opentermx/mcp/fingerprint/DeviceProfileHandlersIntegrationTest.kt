package com.opentermx.mcp.fingerprint

import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.mcp.OpenTermXResources
import com.opentermx.mcp.exec.SessionCommandRunner
import com.opentermx.mcp.handlers.DiagnoseDeviceContextHandler
import com.opentermx.mcp.handlers.FakeDevice
import com.opentermx.mcp.handlers.GetDeviceProfileHandler
import com.opentermx.mcp.handlers.ListDevicesHandler
import com.opentermx.mcp.handlers.ListSessionsHandler
import com.opentermx.mcp.handlers.McpToolException
import com.opentermx.mcp.handlers.RefreshDeviceFingerprintHandler
import com.opentermx.mcp.handlers.TestFixtures
import com.opentermx.mcp.security.ReadOnlyCommandValidator
import com.opentermx.mcp.telemetry.TelemetryStore
import com.opentermx.telemetrydb.DbConfig
import com.opentermx.telemetrydb.TelemetryDb
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Integración 5C end-to-end con PostgreSQL embebido: refresh_device_fingerprint persiste
 * identidad+vecinos, get_device_profile los devuelve (con include selectivo y
 * untrustedFields), list_sessions se enriquece sin romper el shape viejo, list_devices
 * filtra, diagnose_device_context explica, y los resources de perfil/topología responden.
 */
class DeviceProfileHandlersIntegrationTest {

    @TempDir
    lateinit var tmp: Path

    @AfterEach
    fun cleanup() = TestFixtures.unregisterAll()

    private val store = TelemetryStore { EmbPg.db }
    private val validator = ReadOnlyCommandValidator.embedded()
    private val views = DeviceProfileViews(store, validator)

    private fun service(dryRun: Boolean = false) = FingerprintService(
        runner = SessionCommandRunner(pollIntervalMillis = 15, rawSender = { null }),
        validator = validator,
        roleRules = RoleRules.embedded(),
        auditLog = AiAuditLog(tmp.resolve("audit.csv")),
        dryRun = dryRun,
    )

    /** Cada test usa un hostname propio: el upsert por (mgmt_address, port) no debe cruzarse. */
    private fun iosShowVersion(hostname: String) = """
        Cisco IOS Software, C2960X Software (C2960X-UNIVERSALK9-M), Version 15.2(7)E3, RELEASE SOFTWARE (fc2)
        $hostname uptime is 5 weeks, 1 day, 3 hours, 12 minutes
        cisco WS-C2960X-48TS-L (APM86XXX) processor (revision A0) with 524288K bytes of memory.
        Processor board ID FOC1932X0K1
        Model number                    : WS-C2960X-48TS-L
        System serial number            : FOC1932X0K1
    """.trimIndent()

    private val cdpDetail = """
        -------------------------
        Device ID: sw-vecino-01.empresa.local
        Platform: cisco WS-C2960X-48TS-L,  Capabilities: Switch IGMP
        Interface: GigabitEthernet1/0/1,  Port ID (outgoing port): GigabitEthernet0/49
        Holdtime : 132 sec
    """.trimIndent()

    private fun ciscoDevice(host: String, sessionId: String, hostname: String): FakeDevice {
        val device = FakeDevice()
        device.responder = { cmd ->
            val body = when {
                cmd.startsWith("show version") -> iosShowVersion(hostname)
                cmd.startsWith("show cdp") -> cdpDetail
                else -> ""
            }
            listOf("${FakeDevice.PROMPT} $cmd") + body.split('\n') + listOf(FakeDevice.PROMPT)
        }
        device.register(sessionId, host = host)
        return device
    }

    @Test
    fun `refresh persiste y get_device_profile lo devuelve end-to-end`() = runBlocking {
        val host = "10.88.0.1"
        ciscoDevice(host, "fp1", "sw-fp1")
        val refresh = RefreshDeviceFingerprintHandler(service(), store, views)

        val result = refresh.invoke(mapOf("sessionId" to "fp1"))

        assertEquals(true, result["persisted"])
        assertEquals(false, result["dryRun"])
        @Suppress("UNCHECKED_CAST")
        val identity = result["identity"] as Map<String, Any?>
        assertEquals("CISCO_IOS", identity["vendor"])
        assertEquals("WS-C2960X-48TS-L", identity["model"])
        assertEquals("switch", result["roleSuggestion"])

        // El perfil queda consultable por host de la sesión (mgmt_address).
        val profile = GetDeviceProfileHandler(store, views).invoke(mapOf("sessionId" to "fp1"))
        assertEquals(true, profile["found"])
        @Suppress("UNCHECKED_CAST")
        val id = profile["identity"] as Map<String, Any?>
        assertEquals("switch", id["role"])
        assertEquals("INFERRED", id["roleSource"])
        assertEquals("WS-C2960X-48TS-L", id["model"])
        assertNotNull(id["lastFingerprintAt"])
        @Suppress("UNCHECKED_CAST")
        val neighbors = profile["neighbors"] as List<Map<String, Any?>>
        assertEquals(listOf("sw-vecino-01.empresa.local"), neighbors.map { it["remoteHostname"] })
        @Suppress("UNCHECKED_CAST")
        val untrusted = profile["untrustedFields"] as List<String>
        assertTrue("identity.hostname" in untrusted && "neighbors[].remoteHostname" in untrusted)
        @Suppress("UNCHECKED_CAST")
        val allowed = profile["allowedCommands"] as List<String>
        assertTrue(allowed.any { "show" in it }, "patrones de la whitelist, no comandos: $allowed")
    }

    @Test
    fun `include selectivo devuelve solo las secciones pedidas (error 45)`() = runBlocking {
        val host = "10.88.0.2"
        ciscoDevice(host, "fp2", "sw-fp2")
        RefreshDeviceFingerprintHandler(service(), store, views).invoke(mapOf("sessionId" to "fp2"))

        val profile = GetDeviceProfileHandler(store, views)
            .invoke(mapOf("sessionId" to "fp2", "include" to listOf("identity")))

        assertEquals(true, profile["found"])
        assertNotNull(profile["identity"])
        assertNull(profile["neighbors"])
        assertNull(profile["capabilities"])
        assertNull(profile["allowedCommands"])
    }

    @Test
    fun `dry-run ejecuta sondas pero NO persiste`() = runBlocking {
        val host = "10.88.0.3"
        ciscoDevice(host, "fp3", "sw-fp3")
        val result = RefreshDeviceFingerprintHandler(service(dryRun = true), store, views)
            .invoke(mapOf("sessionId" to "fp3"))

        assertEquals(true, result["dryRun"])
        assertEquals(false, result["persisted"])
        @Suppress("UNCHECKED_CAST")
        val identity = result["identity"] as Map<String, Any?>
        assertEquals("WS-C2960X-48TS-L", identity["model"], "el reporte sale igual")
        assertNull(EmbPg.db.devices.findIdByHostname("sw-fp3"), "nada quedó en la base")
        assertNull(EmbPg.db.devices.findIdByMgmtAddress(host), "nada quedó en la base")
    }

    @Test
    fun `list_sessions se enriquece con el perfil sin romper el shape viejo (error 47)`() = runBlocking {
        val host = "10.88.0.4"
        ciscoDevice(host, "fp4", "sw-fp4")
        RefreshDeviceFingerprintHandler(service(), store, views).invoke(mapOf("sessionId" to "fp4"))
        views.invalidate(host)

        @Suppress("UNCHECKED_CAST")
        val enriched = (ListSessionsHandler(views).invoke(emptyMap())["sessions"] as List<Map<String, Any?>>)
            .single { it["host"] == host }
        // Shape viejo intacto + campos nuevos opcionales.
        assertTrue(enriched.keys.containsAll(setOf("sessionId", "protocol", "host", "port", "username", "vendor")))
        assertEquals("switch", enriched["deviceRole"])
        assertEquals("WS-C2960X-48TS-L", enriched["model"])
        assertEquals("medium", enriched["criticality"])
        val summary = enriched["summary"] as String
        assertTrue(summary.length <= 120 && "\n" !in summary && "`" !in summary, "summary sanitizado: $summary")

        // Sin views (clientes/tests viejos) los campos no aparecen.
        @Suppress("UNCHECKED_CAST")
        val plain = (ListSessionsHandler().invoke(emptyMap())["sessions"] as List<Map<String, Any?>>)
            .single { it["host"] == host }
        assertFalse(plain.containsKey("deviceRole"))
        assertFalse(plain.containsKey("summary"))
    }

    @Test
    fun `list_devices filtra por rol y criticidad`() = runBlocking {
        val host = "10.88.0.5"
        ciscoDevice(host, "fp5", "sw-fp5")
        RefreshDeviceFingerprintHandler(service(), store, views).invoke(mapOf("sessionId" to "fp5"))
        val deviceId = EmbPg.db.devices.findIdByHostname("sw-fp5")!!
        EmbPg.db.profiles.updateOperatorFields(deviceId, criticality = "critical")

        val handler = ListDevicesHandler(store)
        @Suppress("UNCHECKED_CAST")
        val criticals = handler.invoke(mapOf("criticality" to "critical"))["devices"] as List<Map<String, Any?>>
        assertTrue(criticals.any { it["hostname"] == "sw-fp5" })

        @Suppress("UNCHECKED_CAST")
        val none = handler.invoke(mapOf("role" to "firewall", "criticality" to "critical"))["devices"] as List<*>
        assertTrue(none.none { (it as Map<*, *>)["hostname"] == "sw-fp5" })
    }

    @Test
    fun `diagnose_device_context explica el ultimo fingerprint`() = runBlocking {
        val host = "10.88.0.6"
        ciscoDevice(host, "fp6", "sw-fp6")
        RefreshDeviceFingerprintHandler(service(), store, views).invoke(mapOf("sessionId" to "fp6"))

        val diag = DiagnoseDeviceContextHandler(store, views).invoke(mapOf("deviceHostname" to "sw-fp6"))

        assertEquals(true, diag["found"])
        @Suppress("UNCHECKED_CAST")
        val last = diag["lastFingerprint"] as Map<String, Any?>
        assertEquals("cisco_show_version", last["probeId"])
        assertEquals("HIGH", last["confidence"])
        assertNotNull(last["traceId"])
        assertNotNull(last["rawExcerpt"], "el excerpt crudo es la evidencia del diagnóstico")
        assertEquals(1, diag["neighborsCount"])
        @Suppress("UNCHECKED_CAST")
        val rag = diag["ragDoc"] as Map<String, Any?>
        assertEquals(false, rag["exists"], "el generador RAG llega en 5D")
    }

    @Test
    fun `resources exponen perfil por hostname y resumen de topologia`() = runBlocking {
        val host = "10.88.0.7"
        ciscoDevice(host, "fp7", "sw-fp7")
        RefreshDeviceFingerprintHandler(service(), store, views).invoke(mapOf("sessionId" to "fp7"))

        val resources = OpenTermXResources(store = store, profileViews = views)
        val uris = resources.list().map { it.uri }
        assertTrue(OpenTermXResources.URI_TOPOLOGY in uris)
        assertTrue(uris.any { it.startsWith("opentermx://devices/") && it.endsWith("/profile") })

        val profile = resources.read(OpenTermXResources.deviceProfileUri("sw-fp7"))
        assertNotNull(profile)
        assertTrue(profile!!.text.contains("\"found\":true"))
        assertTrue(profile.text.contains("WS-C2960X-48TS-L"))

        val topology = resources.read(OpenTermXResources.URI_TOPOLOGY)
        assertNotNull(topology)
        assertTrue(topology!!.text.contains("sw-vecino-01.empresa.local"))
        assertTrue(topology.text.contains("untrustedFields"))
    }

    @Test
    fun `sin BD las tools de perfil devuelven DB_UNAVAILABLE claro`() {
        val noDb = TelemetryStore { null }
        val noDbViews = DeviceProfileViews(noDb, validator)
        val ex = assertThrows(McpToolException::class.java) {
            runBlocking { GetDeviceProfileHandler(noDb, noDbViews).invoke(mapOf("deviceHostname" to "x")) }
        }
        assertEquals(McpToolException.ErrorCode.UNAVAILABLE, ex.code)
        assertTrue(ex.message!!.contains("DB_UNAVAILABLE"))

        val ex2 = assertThrows(McpToolException::class.java) {
            runBlocking { ListDevicesHandler(noDb).invoke(emptyMap()) }
        }
        assertTrue(ex2.message!!.contains("DB_UNAVAILABLE"))
    }

    @Test
    fun `get_device_profile sin sessionId ni hostname es INVALID_ARGUMENT`() {
        val ex = assertThrows(McpToolException::class.java) {
            runBlocking { GetDeviceProfileHandler(store, views).invoke(emptyMap()) }
        }
        assertEquals(McpToolException.ErrorCode.INVALID_ARGUMENT, ex.code)
    }

    private object EmbPg {
        val db: TelemetryDb by lazy {
            val pg = EmbeddedPostgres.builder().start()
            Runtime.getRuntime().addShutdownHook(Thread { runCatching { pg.close() } })
            TelemetryDb.connect(
                DbConfig(host = "localhost", port = pg.port, database = "postgres", username = "postgres", password = "postgres"),
            ).getOrThrow()
        }
    }
}
