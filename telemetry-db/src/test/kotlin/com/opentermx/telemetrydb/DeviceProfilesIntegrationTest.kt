package com.opentermx.telemetrydb

import com.opentermx.fingerprint.Confidence
import com.opentermx.fingerprint.DeviceIdentity
import com.opentermx.fingerprint.neighbors.NeighborEntry
import com.opentermx.fingerprint.neighbors.NeighborProtocol
import com.opentermx.netparsers.Vendor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Integración 5B contra PostgreSQL embebido: migración V2 sobre base con V1, merge por
 * propietario de campo (error #39), reemplazo transaccional de vecinos (error #41),
 * match ambiguo (error #42) y retención de fingerprints (error #43).
 */
class DeviceProfilesIntegrationTest {

    private val db get() = EmbeddedPg.db

    private fun identity(
        model: String? = "WS-C2960X-48TS-L",
        osVersion: String? = "15.2(7)E3",
        vendor: Vendor = Vendor.CISCO_IOS,
        serials: List<String> = listOf("FOC1932X0K1"),
        extras: Map<String, String> = emptyMap(),
    ) = DeviceIdentity(
        vendor = vendor,
        model = model,
        osVersion = osVersion,
        serialNumbers = serials,
        hostname = "sw-test",
        uptimeText = "5 weeks",
        confidence = Confidence.HIGH,
        extras = extras,
    )

    private fun newDevice(ip: String, hostname: String = "sw-prof-$ip"): Long =
        db.devices.upsert(hostname, ip, 22, "SSH", Vendor.CISCO_IOS)!!

    @Test
    fun `la migracion V2 aplica limpia sobre una base con V1 ya migrada`() {
        val tables = db.withConnection { conn ->
            conn.queryToMaps(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'opentermx'"
            ) { }
        }.mapNotNull { it["table_name"] as? String }
        assertTrue(
            tables.containsAll(listOf("device_fingerprints", "device_profiles", "device_neighbors")),
            "faltan tablas V2: $tables",
        )
    }

    @Test
    fun `applyFingerprint persiste historico, actualiza identidad y detecta cambios`() {
        val deviceId = newDevice("10.99.5.1")

        val first = db.profiles.applyFingerprint(
            deviceId, identity(), roleSuggestion = "switch",
            probeId = "cisco_show_version", traceId = "t1", rawExcerpt = "Cisco IOS ...",
        )
        assertNotNull(first)
        assertFalse(first!!.identityChanged, "primer fingerprint: no hay identidad previa que difiera")

        when (val loaded = db.profiles.load(deviceId)) {
            is ProfileRepository.LoadResult.Loaded -> {
                assertEquals("switch", loaded.record.role)
                assertEquals("INFERRED", loaded.record.roleSource)
                assertEquals("medium", loaded.record.criticality)
                assertNotNull(loaded.record.lastFingerprintAt)
                assertEquals("HIGH", loaded.record.lastConfidence)
            }
            ProfileRepository.LoadResult.Missing -> error("el perfil debería existir")
        }

        // Upgrade de OS + cambio de modelo => identityChanged con detalle.
        val second = db.profiles.applyFingerprint(
            deviceId, identity(model = "C9300-48P", osVersion = "17.3.4"),
            roleSuggestion = "switch", probeId = "cisco_show_version", traceId = "t2",
        )
        assertTrue(second!!.identityChanged)
        assertTrue(second.changes.any { "model" in it && "C9300-48P" in it })

        val device = db.withConnection { conn ->
            conn.queryToMaps("SELECT model, os_version FROM devices WHERE id = ?") { it.setLong(1, deviceId) }
        }.single()
        assertEquals("C9300-48P", device["model"])
        assertEquals("17.3.4", device["os_version"])
        assertEquals(2, db.fingerprints.listRecent(deviceId, 10).size, "una fila histórica por fingerprint")
    }

    @Test
    fun `fingerprint nuevo JAMAS pisa los campos del operador (error 39)`() {
        val deviceId = newDevice("10.99.5.2")
        db.profiles.applyFingerprint(
            deviceId, identity(), roleSuggestion = "switch", probeId = "cisco_show_version",
        )

        assertTrue(
            db.profiles.updateOperatorFields(
                deviceId,
                role = "core-switch",
                criticality = "critical",
                notes = "no tocar en horario laboral",
                forbidden = listOf("reload", "write erase"),
                maintenanceWindow = "sab 02:00-04:00",
                contact = "noc@empresa.example",
            )
        )

        // Fingerprint posterior con OTRA sugerencia de rol y otra identidad.
        db.profiles.applyFingerprint(
            deviceId, identity(model = "ISR4331/K9", osVersion = "17.9.1"),
            roleSuggestion = "router", probeId = "cisco_show_version",
        )

        val loaded = db.profiles.load(deviceId) as ProfileRepository.LoadResult.Loaded
        assertEquals("core-switch", loaded.record.role, "el rol confirmado por humano no se pisa")
        assertEquals("OPERATOR", loaded.record.roleSource)
        assertEquals("critical", loaded.record.criticality)
        assertEquals("no tocar en horario laboral", loaded.record.profile["notes"])
        assertEquals("sab 02:00-04:00", loaded.record.profile["maintenanceWindow"])
        val caps = loaded.record.profile["capabilities"] as Map<*, *>
        assertEquals(listOf("reload", "write erase"), caps["forbidden"])
        // La identidad system-owned SÍ se actualizó.
        val device = db.withConnection { conn ->
            conn.queryToMaps("SELECT model FROM devices WHERE id = ?") { it.setLong(1, deviceId) }
        }.single()
        assertEquals("ISR4331/K9", device["model"])
    }

    @Test
    fun `releaseRoleToInferred devuelve el rol al control del sistema`() {
        val deviceId = newDevice("10.99.5.9")
        db.profiles.applyFingerprint(deviceId, identity(), roleSuggestion = "switch", probeId = "cisco_show_version")
        db.profiles.updateOperatorFields(deviceId, role = "core-switch")
        // Confirmado: el fingerprint no lo pisa.
        db.profiles.applyFingerprint(deviceId, identity(), roleSuggestion = "router", probeId = "cisco_show_version")
        var loaded = db.profiles.load(deviceId) as ProfileRepository.LoadResult.Loaded
        assertEquals("core-switch", loaded.record.role)
        assertEquals("OPERATOR", loaded.record.roleSource)

        assertTrue(db.profiles.releaseRoleToInferred(deviceId))
        // Liberado: la próxima sugerencia vuelve a escribir.
        db.profiles.applyFingerprint(deviceId, identity(), roleSuggestion = "router", probeId = "cisco_show_version")
        loaded = db.profiles.load(deviceId) as ProfileRepository.LoadResult.Loaded
        assertEquals("router", loaded.record.role)
        assertEquals("INFERRED", loaded.record.roleSource)
    }

    @Test
    fun `delete del device cascadea perfil, fingerprints y vecinos pero conserva la auditoria`() {
        val deviceId = newDevice("10.99.5.10", hostname = "sw-delete-me")
        db.profiles.applyFingerprint(deviceId, identity(), roleSuggestion = "switch", probeId = "cisco_show_version")
        db.neighbors.replaceAll(
            deviceId,
            listOf(NeighborEntry("Gi1/0/1", "vecino-x", null, protocol = NeighborProtocol.CDP)),
        )
        val sessionUid = "del-test-${deviceId}"
        assertTrue(
            db.audit.insert(
                occurredAt = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC),
                sessionUid = sessionUid, deviceId = deviceId, source = "test",
                vendor = Vendor.CISCO_IOS, readOnly = true, commands = listOf("show version"),
                rationale = null, riskSafe = 1, riskConfig = 0, riskDangerous = 0,
                decision = "AUTO_READONLY", executedCount = 1, rejectedCount = 0,
                outputExcerpt = null,
            )
        )

        assertTrue(db.devices.delete(deviceId))

        assertNull(db.devices.findById(deviceId))
        assertTrue(db.fingerprints.listRecent(deviceId, 10).isEmpty(), "fingerprints cascadeados")
        assertTrue(db.neighbors.list(deviceId).isEmpty(), "vecinos cascadeados")
        assertTrue(db.profiles.load(deviceId) is ProfileRepository.LoadResult.Missing, "perfil cascadeado")
        val audit = db.withConnection { conn ->
            conn.queryToMaps("SELECT device_id FROM command_audit WHERE session_uid = ?") {
                it.setString(1, sessionUid)
            }
        }
        assertEquals(1, audit.size, "la auditoría sobrevive al borrado del device")
        assertNull(audit.single()["device_id"], "…desvinculada (SET NULL)")
        assertFalse(db.devices.delete(deviceId), "segundo delete: ya no existe")
    }

    @Test
    fun `stackMembers de extras queda en profile custom`() {
        val deviceId = newDevice("10.99.5.3")
        db.profiles.applyFingerprint(
            deviceId,
            identity(extras = mapOf("stackMembers" to "C9300-48P,C9300-48P,C9300-24UX")),
            roleSuggestion = "switch", probeId = "cisco_show_version",
        )
        val loaded = db.profiles.load(deviceId) as ProfileRepository.LoadResult.Loaded
        assertEquals(
            "C9300-48P,C9300-48P,C9300-24UX",
            (loaded.record.profile["custom"] as Map<*, *>)["stackMembers"],
        )
    }

    @Test
    fun `JSONB corrupto en la base degrada a perfil minimo sin excepcion (error 40)`() {
        val deviceId = newDevice("10.99.5.4")
        db.profiles.applyFingerprint(deviceId, identity(), probeId = "cisco_show_version")
        // Versión futura directamente en la base (simula un downgrade de OpenTermX).
        db.withConnection { conn ->
            conn.prepareStatement(
                "UPDATE device_profiles SET schema_version = 99, profile = '{\"raro\": true}'::jsonb WHERE device_id = ?"
            ).use { it.setLong(1, deviceId); it.executeUpdate() }
        }
        val loaded = db.profiles.load(deviceId)
        assertTrue(loaded is ProfileRepository.LoadResult.Loaded, "load jamás lanza ni devuelve Missing por shape raro")
        val record = (loaded as ProfileRepository.LoadResult.Loaded).record
        assertTrue(record.profile.containsKey("capabilities"), "perfil mínimo válido")
        assertTrue(loaded.warnings.isNotEmpty())
    }

    @Test
    fun `reemplazo de vecinos es transaccional — un fallo a mitad no deja estado mixto (error 41)`() {
        val deviceId = newDevice("10.99.5.5")
        val old = listOf(
            NeighborEntry("Gi1/0/1", "viejo-1", "Gi0/1", protocol = NeighborProtocol.CDP),
            NeighborEntry("Gi1/0/2", "viejo-2", "Gi0/2", protocol = NeighborProtocol.CDP),
        )
        assertTrue(db.neighbors.replaceAll(deviceId, old))
        assertEquals(2, db.neighbors.list(deviceId).size)

        // El segundo entry viola el CHECK (local_interface vacío) => rollback completo.
        val broken = listOf(
            NeighborEntry("Gi1/0/9", "nuevo-1", null, protocol = NeighborProtocol.LLDP),
            NeighborEntry("", "nuevo-2", null, protocol = NeighborProtocol.LLDP),
        )
        assertFalse(db.neighbors.replaceAll(deviceId, broken))

        val after = db.neighbors.list(deviceId).map { it["remote_hostname"] }
        assertEquals(listOf("viejo-1", "viejo-2"), after, "rollback: los vecinos viejos siguen intactos")

        // Reemplazo válido: bloque nuevo completo, sin zombis del anterior.
        assertTrue(db.neighbors.replaceAll(deviceId, listOf(broken.first())))
        assertEquals(listOf("nuevo-1"), db.neighbors.list(deviceId).map { it["remote_hostname"] })
    }

    @Test
    fun `match de vecinos exige unicidad — hostname ambiguo queda NULL (error 42)`() {
        val deviceId = newDevice("10.99.5.6")
        // Dos devices distintos con el MISMO hostname (sitios que repiten nombres).
        db.devices.upsert("SW-ACCESS-01", "10.99.6.1", 22, "SSH", Vendor.CISCO_IOS)
        db.devices.upsert("SW-ACCESS-01", "10.99.6.2", 22, "SSH", Vendor.CISCO_IOS)
        // Y uno único, anunciado por CDP con dominio.
        db.devices.upsert("sw-unico-99", "10.99.6.3", 22, "SSH", Vendor.CISCO_IOS)

        db.neighbors.replaceAll(
            deviceId,
            listOf(
                NeighborEntry("Gi1/0/1", "SW-ACCESS-01", null, protocol = NeighborProtocol.CDP),
                NeighborEntry("Gi1/0/2", "sw-unico-99.empresa.local", null, protocol = NeighborProtocol.CDP),
                NeighborEntry("Gi1/0/3", "desconocido-77", null, protocol = NeighborProtocol.CDP),
            ),
        )
        val rows = db.neighbors.list(deviceId).associateBy { it["local_interface"] }
        assertNull(rows["Gi1/0/1"]!!["remote_device_id"], "2+ candidatos => no se adivina topología")
        assertNotNull(rows["Gi1/0/2"]!!["remote_device_id"], "FQDN matchea al hostname sin dominio")
        assertEquals(true, rows["Gi1/0/2"]!!["known_device"])
        assertNull(rows["Gi1/0/3"]!!["remote_device_id"])
    }

    @Test
    fun `la retencion conserva los ultimos 20 fingerprints por dispositivo (error 43)`() {
        val deviceId = newDevice("10.99.5.7")
        repeat(25) { i ->
            db.profiles.applyFingerprint(
                deviceId, identity(osVersion = "15.2($i)E"), probeId = "cisco_show_version",
            )
        }
        assertEquals(25, db.fingerprints.listRecent(deviceId, 100).size)

        db.fingerprints.pruneKeepingLast(20)

        val kept = db.fingerprints.listRecent(deviceId, 100)
        assertEquals(20, kept.size)
        assertEquals("15.2(24)E", kept.first()["os_version"], "se conservan los MÁS recientes")
        // El último fingerprint sigue accesible para get_device_profile.
        assertEquals("15.2(24)E", db.fingerprints.latest(deviceId)!!["os_version"])
    }

    @Test
    fun `el trigger toca updated_at en cada update del perfil`() {
        val deviceId = newDevice("10.99.5.8")
        db.profiles.applyFingerprint(deviceId, identity(), probeId = "cisco_show_version")
        val before = (db.profiles.load(deviceId) as ProfileRepository.LoadResult.Loaded).record.updatedAt
        Thread.sleep(20)
        db.profiles.updateOperatorFields(deviceId, notes = "nota nueva")
        val after = (db.profiles.load(deviceId) as ProfileRepository.LoadResult.Loaded).record.updatedAt
        assertNotNull(after)
        assertTrue(after!! > before!!, "updated_at debe avanzar ($before -> $after)")
    }
}
