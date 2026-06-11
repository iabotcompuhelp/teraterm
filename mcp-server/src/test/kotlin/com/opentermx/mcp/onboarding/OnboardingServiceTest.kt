package com.opentermx.mcp.onboarding

import com.opentermx.common.ai.SessionMetadata
import com.opentermx.fingerprint.Confidence
import com.opentermx.fingerprint.DeviceIdentity
import com.opentermx.mcp.telemetry.TelemetryStore
import com.opentermx.netparsers.Vendor
import com.opentermx.telemetrydb.CatalogPackImporter
import com.opentermx.telemetrydb.DbConfig
import com.opentermx.telemetrydb.ProfileRepository
import com.opentermx.telemetrydb.TelemetryDb
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Onboarding al conectar (Fase 6B), lógica de BD: resolución del estado de inventario
 * (incl. reemplazo de hardware por serial distinto — error #51), pre-llenado desde el
 * catálogo (error #54: qué patrón matcheó) y alta confirmada (devices + perfil OPERATOR
 * + métodos de gestión + catalog_model_id).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnboardingServiceTest {

    private val store = TelemetryStore { db }
    private val service = OnboardingService(store)

    @BeforeAll
    fun seedCatalog() {
        CatalogPackImporter(db).importBuiltins() // HPE 5130/5140 + Aruba 2930F
    }

    private fun ssh(host: String, port: Int = 22) =
        SessionMetadata(name = host, protocol = "SSH", host = host, port = port, username = "admin")

    private fun identity(
        model: String? = "2930F",
        vendor: Vendor = Vendor.ARUBA_PROVISION,
        serial: String? = "SG0AKN0123",
        hostname: String? = "sw-acceso-p2",
    ) = DeviceIdentity(
        vendor = vendor, model = model, osVersion = "16.10",
        serialNumbers = serial?.let { listOf(it) } ?: emptyList(),
        hostname = hostname, uptimeText = "5 weeks", confidence = Confidence.HIGH,
    )

    @Test
    fun `host nuevo resuelve NotInventoried`() {
        assertEquals(OnboardingService.Decision.NotInventoried, service.resolve(ssh("10.96.0.1"), identity()))
    }

    @Test
    fun `suggestFrom pre-llena desde el fingerprint y el match del catalogo (error 54)`() {
        val s = service.suggestFrom(identity(model = "Aruba JL255A 2930F-24G Switch"), roleSuggestion = "switch")
        assertEquals("2930F", s.catalogModelName)
        assertEquals("Aruba", s.brandName)
        assertEquals("ArubaOS-Switch", s.family)
        assertEquals("switch", s.deviceType)
        assertEquals("arubaos_switch", s.readonlyProfile)
        assertTrue(s.defaultMethods.contains("REST_API"), "${s.defaultMethods}")
        assertNotNull(s.matchedPattern)
        assertTrue(s.matchedText!!.contains("2930F"), "el operador ve qué matcheó: ${s.matchedText}")
    }

    @Test
    fun `suggestFrom sin match de catalogo cae a CLI_SSH y sin marca`() {
        val s = service.suggestFrom(identity(model = "Equipo-Raro-9000", vendor = Vendor.UNKNOWN), roleSuggestion = null)
        assertNull(s.catalogModelId)
        assertEquals(listOf("CLI_SSH"), s.defaultMethods)
    }

    @Test
    fun `commit da de alta device, perfil OPERATOR, metodos y vinculo al catalogo`() {
        val host = "10.96.0.2"
        val s = service.suggestFrom(identity(model = "Aruba JL255A 2930F"), roleSuggestion = "switch")
        val deviceId = service.commit(
            OnboardingService.Commit(
                metadata = ssh(host),
                identity = identity(),
                hostname = "sw-nuevo-01",
                vendor = Vendor.ARUBA_PROVISION,
                site = "Edificio Central",
                role = "switch",
                criticality = "high",
                notes = "alta por onboarding",
                catalogModelId = s.catalogModelId,
                enabledMethods = listOf("CLI_SSH", "REST_API"),
                probeId = "arubacx_show_system",
                traceId = "ob-1",
                rawExcerpt = "ArubaOS ...",
            )
        )
        assertNotNull(deviceId)

        // Ahora el host está inventariado.
        assertEquals(
            OnboardingService.Decision.AlreadyInventoried(deviceId!!),
            service.resolve(ssh(host), identity()),
        )
        val device = db.devices.findById(deviceId)!!
        assertEquals("sw-nuevo-01", device["hostname"])
        assertEquals("Edificio Central", device["site"])
        assertEquals("SG0AKN0123", device["serial_number"], "el serial del fingerprint quedó")

        val loaded = db.profiles.load(deviceId) as ProfileRepository.LoadResult.Loaded
        assertEquals("switch", loaded.record.role)
        assertEquals("OPERATOR", loaded.record.roleSource, "el operador confirmó el rol en el asistente")
        assertEquals("high", loaded.record.criticality)

        assertEquals("2930F", db.catalog.catalogModelOf(deviceId)?.name)
        val methods = db.catalog.managementSettingsOf(deviceId).filter { it["enabled"] == true }
            .map { it["method"] }
        assertTrue(methods.containsAll(listOf("CLI_SSH", "REST_API")), "$methods")
    }

    @Test
    fun `reemplazo de hardware - misma IP, serial distinto (error 51)`() {
        val host = "10.96.0.3"
        service.commit(
            OnboardingService.Commit(
                metadata = ssh(host), identity = identity(serial = "SERIAL-VIEJO"),
                hostname = "sw-viejo", vendor = Vendor.ARUBA_PROVISION, site = null,
                role = "switch", criticality = "medium", notes = null,
                catalogModelId = null, enabledMethods = listOf("CLI_SSH"),
                probeId = "arubacx_show_system", traceId = "ob-2", rawExcerpt = null,
            )
        )
        // Reconexión a la misma IP, pero el equipo reporta OTRO serial.
        val decision = service.resolve(ssh(host), identity(serial = "SERIAL-NUEVO"))
        assertTrue(decision is OnboardingService.Decision.HardwareReplacement, "$decision")
        decision as OnboardingService.Decision.HardwareReplacement
        assertEquals("SERIAL-VIEJO", decision.storedSerial)
        assertEquals("SERIAL-NUEVO", decision.currentSerial)

        // Mismo serial => sin alerta.
        assertTrue(service.resolve(ssh(host), identity(serial = "SERIAL-VIEJO"))
            is OnboardingService.Decision.AlreadyInventoried)
    }

    @Test
    fun `sin BD resuelve DbUnavailable`() {
        val offline = OnboardingService(TelemetryStore { null })
        assertEquals(OnboardingService.Decision.DbUnavailable, offline.resolve(ssh("10.96.0.9"), identity()))
        assertNull(offline.commit(
            OnboardingService.Commit(
                metadata = ssh("10.96.0.9"), identity = null, hostname = "x", vendor = Vendor.UNKNOWN,
                site = null, role = "unknown", criticality = "medium", notes = null,
                catalogModelId = null, enabledMethods = emptyList(), probeId = null, traceId = null, rawExcerpt = null,
            )
        ))
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
