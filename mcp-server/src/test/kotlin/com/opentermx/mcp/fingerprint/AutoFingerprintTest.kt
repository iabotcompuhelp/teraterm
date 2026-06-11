package com.opentermx.mcp.fingerprint

import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.exec.SessionCommandRunner
import com.opentermx.mcp.handlers.FakeDevice
import com.opentermx.mcp.handlers.TestFixtures
import com.opentermx.mcp.security.ReadOnlyCommandValidator
import com.opentermx.mcp.telemetry.TelemetryStore
import com.opentermx.telemetrydb.DbConfig
import com.opentermx.telemetrydb.TelemetryDb
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Auto-fingerprint al conectar (error #38): TTL por dispositivo, re-sondeo forzado por
 * hostname distinto en el prompt, gates (disabled / sin BD / dry-run / buffer vacío) y
 * el listener de SessionRegistry end-to-end.
 */
class AutoFingerprintTest {

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

    private fun auto(
        service: FingerprintService = service(),
        store: TelemetryStore = this.store,
        enabled: () -> Boolean = { true },
        ttlDays: () -> Int = { 7 },
    ) = AutoFingerprint(
        service = service,
        store = store,
        persister = FingerprintPersister(store, views),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        enabled = enabled,
        ttlDays = ttlDays,
        settleDelayMillis = 10,
        settleAttempts = 2,
    )

    /** El hostname del fixture coincide con el prompt del FakeDevice (router-1#). */
    private fun iosShowVersion(hostname: String) = """
        Cisco IOS Software, C2960X Software (C2960X-UNIVERSALK9-M), Version 15.2(7)E3, RELEASE SOFTWARE (fc2)
        $hostname uptime is 5 weeks, 1 day, 3 hours, 12 minutes
        cisco WS-C2960X-48TS-L (APM86XXX) processor (revision A0) with 524288K bytes of memory.
        Processor board ID FOC1932X0K1
    """.trimIndent()

    private fun ciscoDevice(
        host: String,
        sessionId: String,
        hostname: String = "router-1",
        prompt: String = FakeDevice.PROMPT,
    ): FakeDevice {
        val device = FakeDevice(
            initialBuffer = listOf("Cisco IOS Software, Version 15.2", prompt),
            prompt = prompt,
        )
        device.responder = { cmd ->
            val body = when {
                cmd.startsWith("show version") -> iosShowVersion(hostname)
                else -> ""
            }
            listOf("$prompt $cmd") + body.split('\n') + listOf(prompt)
        }
        device.register(sessionId, host = host)
        return device
    }

    @Test
    fun `primera conexion sondea, persiste, y dentro del TTL no vuelve a tocar el equipo`() = runBlocking {
        val host = "10.91.0.1"
        val device = ciscoDevice(host, "auto1")
        val auto = auto()

        assertEquals(AutoFingerprint.Outcome.RAN, auto.maybeFingerprint(SessionId("auto1")))
        val deviceId = EmbPg.db.devices.findIdByMgmtAddress(host)
        assertNotNull(deviceId, "la primera conexión persiste el perfil")
        assertNotNull(EmbPg.db.fingerprints.latest(deviceId!!))
        val commandsAfterFirst = device.received.size
        assertTrue(device.received.any { it.startsWith("show version") })

        // Reconexión (otra sesión, mismo equipo) dentro del TTL: ni un comando.
        ciscoDevice(host, "auto1b")
        val again = auto.maybeFingerprint(SessionId("auto1b"))
        assertEquals(AutoFingerprint.Outcome.SKIPPED_FRESH, again)
        assertEquals(commandsAfterFirst, device.received.size, "el equipo original no recibió nada nuevo")
        assertEquals(1, EmbPg.db.fingerprints.listRecent(deviceId, 10).size, "sigue habiendo UN fingerprint")
    }

    @Test
    fun `ttl vencido re-sondea en la proxima conexion`() = runBlocking {
        val host = "10.91.0.2"
        ciscoDevice(host, "auto2")
        val auto = auto()
        assertEquals(AutoFingerprint.Outcome.RAN, auto.maybeFingerprint(SessionId("auto2")))
        val deviceId = EmbPg.db.devices.findIdByMgmtAddress(host)!!

        // Envejecer el último fingerprint más allá del TTL de 7 días.
        EmbPg.db.withConnection { conn ->
            conn.prepareStatement(
                "UPDATE device_fingerprints SET taken_at = now() - interval '8 days' WHERE device_id = ?"
            ).use { it.setLong(1, deviceId); it.executeUpdate() }
        }

        assertEquals(AutoFingerprint.Outcome.RAN, auto.maybeFingerprint(SessionId("auto2")))
        assertEquals(2, EmbPg.db.fingerprints.listRecent(deviceId, 10).size, "histórico con ambos fingerprints")
    }

    @Test
    fun `hostname distinto en el prompt fuerza re-sondeo aunque el TTL este fresco`() = runBlocking {
        val host = "10.91.0.3"
        ciscoDevice(host, "auto3", hostname = "router-1")
        val auto = auto()
        assertEquals(AutoFingerprint.Outcome.RAN, auto.maybeFingerprint(SessionId("auto3")))
        val deviceId = EmbPg.db.devices.findIdByMgmtAddress(host)!!

        // Misma IP, pero el prompt anuncia otro hostname: ¿reemplazo de hardware?
        ciscoDevice(host, "auto3b", hostname = "switch-nuevo", prompt = "switch-nuevo#")
        assertEquals(AutoFingerprint.Outcome.RAN, auto.maybeFingerprint(SessionId("auto3b")))
        assertEquals("switch-nuevo", EmbPg.db.fingerprints.latest(deviceId)!!["hostname"])
    }

    @Test
    fun `deshabilitado o sin BD no manda comandos`() = runBlocking {
        val device = ciscoDevice("10.91.0.4", "auto4")

        assertEquals(
            AutoFingerprint.Outcome.SKIPPED_DISABLED,
            auto(enabled = { false }).maybeFingerprint(SessionId("auto4")),
        )
        assertEquals(
            AutoFingerprint.Outcome.SKIPPED_NO_DB,
            auto(store = TelemetryStore { null }).maybeFingerprint(SessionId("auto4")),
        )
        assertTrue(device.received.isEmpty(), "ningún comando llegó al equipo")
    }

    @Test
    fun `dry-run sondea y loguea pero no persiste`() = runBlocking {
        val host = "10.91.0.5"
        val device = ciscoDevice(host, "auto5")

        val outcome = auto(service = service(dryRun = true)).maybeFingerprint(SessionId("auto5"))

        assertEquals(AutoFingerprint.Outcome.RAN_DRY, outcome)
        assertTrue(device.received.any { it.startsWith("show version") }, "las sondas SÍ corren")
        assertNull(EmbPg.db.devices.findIdByMgmtAddress(host), "nada quedó en la base")
    }

    @Test
    fun `sesion sin output tras el settle se saltea sin mandar comandos`() = runBlocking {
        val device = FakeDevice(initialBuffer = emptyList())
        device.register("auto6", host = "10.91.0.6")

        assertEquals(
            AutoFingerprint.Outcome.SKIPPED_NOT_READY,
            auto().maybeFingerprint(SessionId("auto6")),
        )
        assertTrue(device.received.isEmpty())
    }

    @Test
    fun `el listener de SessionRegistry dispara el fingerprint en background`() = runBlocking {
        val host = "10.91.0.7"
        val auto = auto()
        val subscription = auto.start()
        try {
            ciscoDevice(host, "auto7") // register() emite SessionChange.Opened
            var deviceId: Long? = null
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                deviceId = EmbPg.db.devices.findIdByMgmtAddress(host)
                if (deviceId != null && EmbPg.db.fingerprints.latest(deviceId) != null) break
                delay(50)
            }
            assertNotNull(deviceId, "la sesión abierta terminó fingerprinteada en background")
            assertNotNull(EmbPg.db.fingerprints.latest(deviceId!!))
        } finally {
            subscription.close()
        }
    }

    @Test
    fun `promptHostname reconoce los prompts de las CLIs soportadas`() {
        assertEquals("sw-acceso-p2", AutoFingerprint.promptHostname("sw-acceso-p2#"))
        assertEquals("sw-acceso-p2", AutoFingerprint.promptHostname("sw-acceso-p2(config)#"))
        assertEquals("fw-perimetro", AutoFingerprint.promptHostname("fw-perimetro # "))
        assertEquals("sw-huawei", AutoFingerprint.promptHostname("<sw-huawei>"))
        assertEquals("sw-huawei", AutoFingerprint.promptHostname("[sw-huawei]"))
        assertEquals("mk-core", AutoFingerprint.promptHostname("[admin@mk-core] > "))
        assertEquals("mk-core", AutoFingerprint.promptHostname("[admin@mk-core/interface] >"))
        assertNull(AutoFingerprint.promptHostname("% Invalid input detected"))
        assertNull(AutoFingerprint.promptHostname("Username:"))
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
