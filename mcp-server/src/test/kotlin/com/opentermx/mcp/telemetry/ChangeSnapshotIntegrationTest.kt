package com.opentermx.mcp.telemetry

import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.mcp.exec.SessionCommandRunner
import com.opentermx.mcp.handlers.FakeApprovalGate
import com.opentermx.mcp.handlers.FakeDevice
import com.opentermx.mcp.handlers.ProposeCommandsHandler
import com.opentermx.mcp.handlers.TestFixtures
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Regla 2 de la Fase 3: `propose_commands` aprobado con comandos CONFIG captura la
 * running-config ANTES y DESPUÉS del cambio (config_snapshots con trigger
 * pre_change/post_change) y persiste el diff (config_diffs). Best-effort: sin BD o con
 * comandos solo-SAFE no se captura nada y la ejecución no se ve afectada.
 */
class ChangeSnapshotIntegrationTest {

    @TempDir
    lateinit var tmp: Path

    @AfterEach
    fun cleanup() = TestFixtures.unregisterAll()

    private val store = TelemetryStore { EmbPg.db }

    private fun newRunner() = SessionCommandRunner(pollIntervalMillis = 15, rawSender = { null })

    private fun capture(store: TelemetryStore = this.store) = ChangeSnapshotCapture(
        store = store,
        runner = newRunner(),
        postChangeSettleMillis = 10,
    )

    private fun handler(
        gate: FakeApprovalGate,
        changeSnapshots: ChangeSnapshotCapture?,
    ) = ProposeCommandsHandler(
        gate,
        auditLog = AiAuditLog(tmp.resolve("audit.csv")),
        injectDelayMillis = 0,
        changeSnapshots = changeSnapshots,
    )

    /** Running-config versionada: los comandos de config mutan el estado del fake. */
    private fun runningConfig(version: Int) = buildString {
        appendLine("hostname sw-snap")
        appendLine("interface GigabitEthernet0/1")
        if (version >= 2) appendLine(" description NUEVA-DESC")
        appendLine(" no shutdown")
        appendLine("line vty 0 4")
        appendLine(" login")
    }.trimEnd()

    private fun ciscoDevice(host: String, sessionId: String): FakeDevice {
        val device = FakeDevice()
        var configVersion = 1
        device.responder = { cmd ->
            when {
                cmd.startsWith("show running-config") ->
                    listOf("${FakeDevice.PROMPT} $cmd") + runningConfig(configVersion).split('\n') +
                        listOf(FakeDevice.PROMPT)
                cmd.startsWith("description") -> {
                    configVersion = 2
                    listOf("${FakeDevice.PROMPT} $cmd", FakeDevice.PROMPT)
                }
                else -> listOf("${FakeDevice.PROMPT} $cmd", FakeDevice.PROMPT)
            }
        }
        device.register(sessionId, host = host)
        return device
    }

    private val configCommands = listOf(
        "configure terminal",
        "interface GigabitEthernet0/1",
        "description NUEVA-DESC",
        "end",
    )

    @Test
    fun `cambio aprobado captura pre y post y persiste el diff`() = runBlocking {
        val host = "10.92.0.1"
        val device = ciscoDevice(host, "snap1")
        val gate = FakeApprovalGate().apply { approveAll() }

        val result = handler(gate, capture()).invoke(
            mapOf("sessionId" to "snap1", "commands" to configCommands),
        )

        assertEquals(true, result["approved"])
        assertEquals(4, result["executed"])
        val preId = result["preSnapshotId"] as? Long
        val postId = result["postSnapshotId"] as? Long
        val diffId = result["configDiffId"] as? Long
        assertNotNull(preId, "snapshot pre_change: $result")
        assertNotNull(postId, "snapshot post_change: $result")
        assertNotNull(diffId, "la config cambió => diff persistido: $result")

        // El pre se capturó ANTES de inyectar y el post DESPUÉS.
        val showIdx = device.received.withIndex().filter { it.value.startsWith("show running-config") }
        val descIdx = device.received.indexOfFirst { it.startsWith("description") }
        assertEquals(2, showIdx.size)
        assertTrue(showIdx.first().index < descIdx && descIdx < showIdx.last().index)

        val rows = EmbPg.db.withConnection { conn ->
            conn.queryToMapsPublic(
                "SELECT s.\"trigger\", s.config_text FROM config_snapshots s WHERE s.id IN (?, ?) ORDER BY s.id",
                preId!!, postId!!,
            )
        }
        assertEquals(listOf("pre_change", "post_change"), rows.map { it["trigger"] })
        assertFalse(rows.first()["config_text"].toString().contains("NUEVA-DESC"))
        assertTrue(rows.last()["config_text"].toString().contains("NUEVA-DESC"))

        val diff = EmbPg.db.withConnection { conn ->
            conn.queryToMapsPublic("SELECT * FROM config_diffs WHERE id = ?", diffId!!)
        }.single()
        assertEquals(1L, (diff["lines_added"] as Number).toLong())
        val unified = diff["unified_diff"].toString()
        assertTrue("NUEVA-DESC" in unified, "el diff muestra la línea agregada: $unified")
        assertTrue("@@ interface GigabitEthernet0/1" in unified, "agrupado por sección (cisco_ios): $unified")
        assertEquals(preId, (diff["from_snapshot_id"] as Number).toLong())
        assertEquals(postId, (diff["to_snapshot_id"] as Number).toLong())
    }

    @Test
    fun `config sin cambios reales captura pre y post pero NO persiste diff`() = runBlocking {
        val host = "10.92.0.2"
        // Responder que nunca muta la config (los comandos no tocan `description`).
        val device = FakeDevice()
        device.responder = { cmd ->
            if (cmd.startsWith("show running-config")) {
                listOf("${FakeDevice.PROMPT} $cmd") + runningConfig(1).split('\n') + listOf(FakeDevice.PROMPT)
            } else listOf("${FakeDevice.PROMPT} $cmd", FakeDevice.PROMPT)
        }
        device.register("snap2", host = host)
        val gate = FakeApprovalGate().apply { approveAll() }

        val result = handler(gate, capture()).invoke(
            mapOf("sessionId" to "snap2", "commands" to listOf("configure terminal", "end")),
        )

        assertNotNull(result["preSnapshotId"])
        assertNotNull(result["postSnapshotId"])
        assertNull(result["configDiffId"], "hash idéntico => sin diff fantasma")
    }

    @Test
    fun `comandos solo-SAFE aprobados no capturan running-config`() = runBlocking {
        val device = ciscoDevice("10.92.0.3", "snap3")
        val gate = FakeApprovalGate().apply { approveAll() }

        val result = handler(gate, capture()).invoke(
            mapOf("sessionId" to "snap3", "commands" to listOf("show version", "show ip interface brief")),
        )

        assertEquals(true, result["approved"])
        assertFalse(result.containsKey("preSnapshotId"), "sin CONFIG/DANGEROUS no hay snapshot")
        assertTrue(device.received.none { it.startsWith("show running-config") })
    }

    @Test
    fun `sin BD la ejecucion aprobada sigue igual y sin claves de snapshot`() = runBlocking {
        val device = ciscoDevice("10.92.0.4", "snap4")
        val gate = FakeApprovalGate().apply { approveAll() }

        val result = handler(gate, capture(store = TelemetryStore { null })).invoke(
            mapOf("sessionId" to "snap4", "commands" to configCommands),
        )

        assertEquals(true, result["approved"])
        assertEquals(4, result["executed"], "la telemetría jamás bloquea el cambio aprobado")
        assertFalse(result.containsKey("preSnapshotId"))
        assertTrue(device.received.none { it.startsWith("show running-config") })
    }

    @Test
    fun `rechazo del operador no captura nada`() = runBlocking {
        val device = ciscoDevice("10.92.0.5", "snap5")
        val gate = FakeApprovalGate().apply { rejectAll() }

        val result = handler(gate, capture()).invoke(
            mapOf("sessionId" to "snap5", "commands" to configCommands),
        )

        assertEquals(false, result["approved"])
        assertTrue(device.received.isEmpty(), "ni captura ni comandos: no se aprobó nada")
        assertFalse(result.containsKey("preSnapshotId"))
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

/** Mini-helper local: query con binds posicionales Long → List<Map>. */
private fun java.sql.Connection.queryToMapsPublic(sql: String, vararg ids: Long): List<Map<String, Any?>> =
    prepareStatement(sql).use { ps ->
        ids.forEachIndexed { i, id -> ps.setLong(i + 1, id) }
        ps.executeQuery().use { rs ->
            val meta = rs.metaData
            val out = mutableListOf<Map<String, Any?>>()
            while (rs.next()) {
                out += (1..meta.columnCount).associate { c -> meta.getColumnLabel(c) to rs.getObject(c) }
            }
            out
        }
    }
