package com.opentermx.mcp.telemetry

import com.opentermx.ai.audit.AiAuditEntry
import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.ai.safety.RiskLevel
import com.opentermx.mcp.exec.SessionCommandRunner
import com.opentermx.mcp.handlers.FakeDevice
import com.opentermx.mcp.handlers.GetDeviceHistoryHandler
import com.opentermx.mcp.handlers.GetInterfaceStatsHandler
import com.opentermx.mcp.handlers.McpToolException
import com.opentermx.mcp.handlers.TestFixtures
import com.opentermx.telemetrydb.DbConfig
import com.opentermx.telemetrydb.TelemetryDb
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Integración Fase 3 (criterio de aceptación): `get_interface_stats` contra un device
 * fake persiste en `interface_metrics` y la vista `v_latest_interface_status` lo refleja.
 * También: transición de estado => link_event vía scheduler, import legacy idempotente
 * y `get_device_history` end-to-end. PostgreSQL real embebido (zonky, sin Docker).
 */
class TelemetryPersistenceIntegrationTest {

    @TempDir
    lateinit var tmp: Path

    @AfterEach
    fun cleanup() = TestFixtures.unregisterAll()

    private fun newRunner() = SessionCommandRunner(pollIntervalMillis = 15, rawSender = { null })

    /** Output IOS mínimo parametrizable por estado del enlace. */
    private fun iosOutput(operStatus: String) = """
        GigabitEthernet0/1 is $operStatus, line protocol is $operStatus
          Description: UPLINK-TEST
          MTU 1500 bytes, BW 1000000 Kbit/sec, DLY 10 usec,
          Full-duplex, 1000Mb/s, media type is 10/100/1000BaseTX
          Input queue: 0/75/12/0 (size/max/drops/flushes); Total output drops: 35
          5 minute input rate 2304000 bits/sec, 412 packets/sec
          5 minute output rate 1841000 bits/sec, 389 packets/sec
             58422345 packets input, 4123456789 bytes, 0 no buffer
             142 input errors, 89 CRC, 0 frame, 0 overrun, 0 ignored
             49283746 packets output, 3987654321 bytes, 0 underruns
             0 output errors, 3 collisions, 1 interface resets
    """.trimIndent()

    private fun ciscoDevice(host: String, oper: () -> String): FakeDevice {
        val device = FakeDevice()
        device.responder = { cmd ->
            if (cmd.startsWith("show interfaces")) {
                listOf("${FakeDevice.PROMPT} $cmd") + iosOutput(oper()).split('\n') + listOf(FakeDevice.PROMPT)
            } else listOf("${FakeDevice.PROMPT} $cmd", FakeDevice.PROMPT)
        }
        return device
    }

    @Test
    fun `get_interface_stats persiste y v_latest_interface_status lo refleja`() = runBlocking {
        val db = EmbPg.db
        val store = TelemetryStore { db }
        val host = "10.77.0.1"
        val device = ciscoDevice(host) { "up" }
        TestFixtures.unregisterAll()
        registerWithHost(device, "s-persist", host)

        val handler = GetInterfaceStatsHandler(
            newRunner(), AiAuditLog(tmp.resolve("a.csv")), store = store,
        )
        val result = handler.invoke(mapOf("sessionId" to "s-persist", "persist" to true))

        assertEquals(true, result["parsed"])
        assertEquals(true, result["persisted"], "con BD disponible la muestra debe persistirse")
        val latest = db.history.latestInterfaceStatus(host)
        assertEquals(1, latest.size, "v_latest debe reflejar la muestra: $latest")
        assertEquals("GigabitEthernet0/1", latest.first()["interface"])
        assertEquals(142L, latest.first()["input_errors"])

        // Histórico consultable por la tool.
        val history = GetDeviceHistoryHandler(store).invoke(
            mapOf("deviceHostname" to host, "dataType" to "interface_metrics"),
        )
        assertTrue((history["count"] as Int) >= 1)
    }

    @Test
    fun `el scheduler detecta transicion de enlace y la registra en link_events`() = runBlocking {
        val db = EmbPg.db
        val store = TelemetryStore { db }
        val host = "10.77.0.2"
        var oper = "up"
        val device = ciscoDevice(host) { oper }
        registerWithHost(device, "s-sched", host)

        val scheduler = MetricsPollScheduler(store, newRunner(), commandTimeoutMillis = 5_000)
        scheduler.pollOnce()                       // muestra 1: UP
        oper = "down"
        scheduler.pollOnce()                       // muestra 2: DOWN => LINK_DOWN

        val events = db.history.linkEvents(host, "GigabitEthernet0/1", null, null, 10)
        assertEquals(1, events.size, "una transición UP→DOWN debe generar un evento: $events")
        assertEquals("LINK_DOWN", events.first()["event"])
        assertEquals("UP", events.first()["prev_status"])
        assertEquals("DOWN", events.first()["new_status"])
    }

    @Test
    fun `import del CSV legacy es idempotente`() {
        val db = EmbPg.db
        val csv = AiAuditLog(tmp.resolve("legacy.csv"))
        csv.append(
            AiAuditEntry(
                timestampMillis = 1_750_000_000_000, sessionId = "legacy-1", host = "1.2.3.4",
                vendor = "Cisco IOS", prompt = "verificación", commands = listOf("show version"),
                commandRisks = listOf(RiskLevel.SAFE), executedCount = 1, skippedCount = 0,
                failedCount = 0, rejected = false, outputTail = "ok",
            )
        )
        csv.append(
            AiAuditEntry(
                timestampMillis = 1_750_000_100_000, sessionId = "legacy-2", host = "1.2.3.4",
                vendor = "Cisco IOS", prompt = "(mcp run_readonly_command)", commands = listOf("show ip route"),
                commandRisks = listOf(RiskLevel.SAFE), executedCount = 1, skippedCount = 0,
                failedCount = 0, rejected = false, outputTail = "ok",
            )
        )

        val first = ImportLegacyAuditCsv.run(csv, db)
        assertEquals(2, first.imported, "primera corrida importa todo")
        val second = ImportLegacyAuditCsv.run(csv, db)
        assertEquals(0, second.imported, "segunda corrida no duplica")
        assertEquals(2, second.skipped)
    }

    @Test
    fun `get_device_history sin BD devuelve DB_UNAVAILABLE`() {
        val handler = GetDeviceHistoryHandler(TelemetryStore { null })
        val ex = org.junit.jupiter.api.Assertions.assertThrows(McpToolException::class.java) {
            runBlocking { handler.invoke(mapOf("deviceHostname" to "x", "dataType" to "link_events")) }
        }
        assertEquals(McpToolException.ErrorCode.UNAVAILABLE, ex.code)
        assertTrue(ex.message!!.contains("DB_UNAVAILABLE"))
    }

    private fun registerWithHost(device: FakeDevice, idValue: String, host: String) {
        com.opentermx.common.ai.SessionRegistry.register(
            com.opentermx.common.session.SessionId(idValue),
            com.opentermx.common.ai.SessionMetadata(
                name = idValue, protocol = "SSH", host = host, port = 22, username = "admin",
            ),
            device.provider,
            device.sink,
        )
    }

    /** Embedded PG compartido por los tests de integración de este módulo. */
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
