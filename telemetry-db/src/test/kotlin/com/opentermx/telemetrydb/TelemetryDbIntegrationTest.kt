package com.opentermx.telemetrydb

import com.opentermx.netparsers.InterfaceStats
import com.opentermx.netparsers.PortStatus
import com.opentermx.netparsers.Vendor
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Integración contra PostgreSQL real embebido (zonky, sin Docker): migración Flyway,
 * roundtrip completo device → interface → metric (con partición on-demand), vista
 * v_latest_interface_status, link events, snapshots sanitizados, audit idempotente
 * y mantenimiento de particiones.
 */
class TelemetryDbIntegrationTest {

    private val db get() = EmbeddedPg.db

    @Test
    fun `la migracion aplica limpia y es idempotente`() {
        assertTrue(db.isAvailable())
        // Segunda conexión sobre el mismo schema: Flyway no debe fallar ni re-aplicar.
        TelemetryDb.connect(EmbeddedPg.config()).getOrThrow().use { second ->
            assertTrue(second.isAvailable())
        }
        val tables = db.withConnection { conn ->
            conn.queryToMaps(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'opentermx'"
            ) { }
        }.mapNotNull { it["table_name"] as? String }
        assertTrue(
            tables.containsAll(
                listOf(
                    "devices", "interfaces", "sessions_log", "command_audit",
                    "config_snapshots", "config_diffs", "interface_metrics",
                    "link_events", "monitoring_integrations", "device_external_refs",
                )
            ),
            "faltan tablas: $tables",
        )
    }

    @Test
    fun `roundtrip metric con particion on-demand y vista v_latest`() {
        val deviceId = db.devices.upsert("sw-test-01", "10.99.0.1", 22, "SSH", Vendor.CISCO_IOS)
        assertNotNull(deviceId)
        val ifaceId = db.interfaces.upsert(deviceId!!, "GigabitEthernet0/1", "UPLINK", 1_000_000_000L)
        assertNotNull(ifaceId)

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val stats = InterfaceStats(
            name = "GigabitEthernet0/1",
            adminStatus = PortStatus.UP,
            operStatus = PortStatus.UP,
            inputRateBps = 2_304_000, outputRateBps = 1_841_000,
            inputErrors = 142, crcErrors = 89, inputDrops = 12, outputDrops = 35,
        )
        assertTrue(db.metrics.insert(ifaceId!!, now, stats, utilizationInPct = 0.23))
        assertEquals(PortStatus.UP, db.metrics.lastOperStatus(ifaceId))

        val latest = db.history.latestInterfaceStatus("sw-test-01")
        assertEquals(1, latest.size, "la vista debe reflejar la muestra: $latest")
        assertEquals("GigabitEthernet0/1", latest.first()["interface"])
        assertEquals("UP", latest.first()["oper_status"].toString())
        assertEquals(142L, latest.first()["input_errors"])

        // Historia con filtros.
        val rows = db.history.interfaceMetrics("sw-test-01", "GigabitEthernet0/1", null, null, 10)
        assertEquals(1, rows.size)
        assertEquals(2_304_000L, rows.first()["input_rate_bps"])
    }

    @Test
    fun `upsert de device es estable por mgmt_address+port y hostname DNS degrada a null`() {
        val first = db.devices.upsert("r1", "10.99.0.2", 22, "SSH", Vendor.CISCO_IOS)
        val second = db.devices.upsert("r1-renombrado", "10.99.0.2", 22, "SSH", Vendor.CISCO_IOSXE)
        assertEquals(first, second, "misma IP:puerto => mismo device")
        assertNull(
            db.devices.upsert("x", "router-cisco.lab", 22, "SSH", Vendor.CISCO_IOS),
            "un hostname DNS no es INET: degradar a null, no inventar",
        )
    }

    @Test
    fun `link events y transiciones`() {
        val deviceId = db.devices.upsert("sw-test-02", "10.99.0.3", 22, "SSH", Vendor.CISCO_IOS)!!
        val ifaceId = db.interfaces.upsert(deviceId, "Gi0/5", null, null)!!
        assertTrue(db.linkEvents.insert(ifaceId, "LINK_DOWN", PortStatus.UP, PortStatus.DOWN))
        val events = db.history.linkEvents("sw-test-02", "Gi0/5", null, null, 10)
        assertEquals(1, events.size)
        assertEquals("LINK_DOWN", events.first()["event"])
        assertEquals("UP", events.first()["prev_status"])
    }

    @Test
    fun `snapshot persiste sanitizado y el diff se vincula`() {
        val deviceId = db.devices.upsert("fw-test-01", "10.99.0.4", 22, "SSH", Vendor.FORTINET)!!
        val rawConfig = "hostname FW1\nenable secret 5 \$1\$abc\$SECRETO999\ninterface port1"
        val snapA = db.snapshots.insert(deviceId, "pre_change", rawConfig)
        assertNotNull(snapA)
        val stored = db.withConnection { conn ->
            conn.queryToMaps("SELECT config_text FROM config_snapshots WHERE id = ?") { ps ->
                ps.setLong(1, snapA!!)
            }
        }.first()["config_text"] as String
        assertFalse(stored.contains("SECRETO999"), "config_text jamás guarda el secreto")
        assertTrue(stored.contains("<REDACTED>"))

        val snapB = db.snapshots.insert(deviceId, "post_change", rawConfig + "\ninterface port2")!!
        val diffId = db.snapshots.insertDiff(deviceId, snapA!!, snapB, "+interface port2", 1, 0)
        assertNotNull(diffId)
        val diffs = db.history.configDiffs("fw-test-01", null, null, 10)
        assertEquals(1, diffs.size)
        assertEquals(1, diffs.first()["lines_added"])
    }

    @Test
    fun `audit con legacy hash es idempotente`() {
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        fun insert() = db.audit.insert(
            occurredAt = now, sessionUid = "sess-legacy-1", deviceId = null,
            source = "legacy_csv_import", vendor = Vendor.CISCO_IOS, readOnly = false,
            commands = listOf("show version", "configure terminal"),
            rationale = "import test", riskSafe = 1, riskConfig = 1, riskDangerous = 0,
            decision = "APPROVED", executedCount = 2, rejectedCount = 0,
            outputExcerpt = "ok", legacyRowHash = "hash-fijo-para-idempotencia-001",
        )
        assertTrue(insert(), "la primera inserción entra")
        assertFalse(insert(), "la segunda con el mismo hash NO duplica")

        val count = db.withConnection { conn ->
            conn.queryToMaps("SELECT count(*) AS c FROM command_audit WHERE legacy_row_hash = ?") { ps ->
                ps.setString(1, "hash-fijo-para-idempotencia-001")
            }
        }.first()["c"]
        assertEquals(1L, count)
    }

    @Test
    fun `mantenimiento crea particion del mes proximo y borra las viejas`() {
        // Muestra vieja (enero 2026): fuerza partición on-demand del mes viejo.
        val deviceId = db.devices.upsert("sw-test-03", "10.99.0.5", 22, "SSH", Vendor.CISCO_IOS)!!
        val ifaceId = db.interfaces.upsert(deviceId, "Gi0/9", null, null)!!
        val january = OffsetDateTime.of(2026, 1, 15, 12, 0, 0, 0, ZoneOffset.UTC)
        assertTrue(db.metrics.insert(ifaceId, january, InterfaceStats(name = "Gi0/9", operStatus = PortStatus.UP)))
        assertTrue(partitionNames().contains("interface_metrics_2026_01"))

        db.maintenance.runDaily(retentionDays = 90)

        val partitions = partitionNames()
        assertFalse(partitions.contains("interface_metrics_2026_01"), "enero excede los 90 días de retención")
        val nextMonth = java.time.YearMonth.now(ZoneOffset.UTC).plusMonths(1)
        assertTrue(
            partitions.contains("interface_metrics_%04d_%02d".format(nextMonth.year, nextMonth.monthValue)),
            "la partición del mes próximo debe pre-crearse: $partitions",
        )
    }

    private fun partitionNames(): List<String> = db.withConnection { conn ->
        conn.queryToMaps(
            """
            SELECT c.relname AS name FROM pg_inherits h
            JOIN pg_class c ON c.oid = h.inhrelid
            JOIN pg_class p ON p.oid = h.inhparent
            WHERE p.relname = 'interface_metrics'
            """.trimIndent()
        ) { }
    }.mapNotNull { it["name"] as? String }
}
