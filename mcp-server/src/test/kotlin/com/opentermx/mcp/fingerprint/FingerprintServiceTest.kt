package com.opentermx.mcp.fingerprint

import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.fingerprint.Confidence
import com.opentermx.mcp.exec.SessionCommandRunner
import com.opentermx.mcp.handlers.FakeDevice
import com.opentermx.mcp.handlers.TestFixtures
import com.opentermx.mcp.security.ReadOnlyCommandValidator
import com.opentermx.netparsers.Vendor
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FingerprintServiceTest {

    @TempDir
    lateinit var tmp: Path

    @AfterEach
    fun cleanup() = TestFixtures.unregisterAll()

    private fun newService(
        validator: ReadOnlyCommandValidator = ReadOnlyCommandValidator.embedded(),
        activeProbing: Boolean = false,
    ) = FingerprintService(
        runner = SessionCommandRunner(pollIntervalMillis = 15, rawSender = { null }),
        validator = validator,
        roleRules = RoleRules.embedded(),
        auditLog = AiAuditLog(tmp.resolve("audit.csv")),
        activeProbing = activeProbing,
    )

    private val iosShowVersion = """
        Cisco IOS Software, C2960X Software (C2960X-UNIVERSALK9-M), Version 15.2(7)E3, RELEASE SOFTWARE (fc2)
        sw-acceso-p2 uptime is 5 weeks, 1 day, 3 hours, 12 minutes
        cisco WS-C2960X-48TS-L (APM86XXX) processor (revision A0) with 524288K bytes of memory.
        Processor board ID FOC1932X0K1
        Model number                    : WS-C2960X-48TS-L
        System serial number            : FOC1932X0K1
    """.trimIndent()

    private val cdpDetail = """
        -------------------------
        Device ID: sw-acceso-01.empresa.local
        Platform: cisco WS-C2960X-48TS-L,  Capabilities: Switch IGMP
        Interface: GigabitEthernet1/0/1,  Port ID (outgoing port): GigabitEthernet0/49
        Holdtime : 132 sec
    """.trimIndent()

    private val arubaShowSystem = """
        Hostname                : sw-piso3-idf2
        Product Name            : JL658A 6300M 24SFP+ 4SFP56 Swch
        Chassis Serial Nbr      : SG0AKN0123
        ArubaOS-CX Version      : FL.10.06.0010
        Up Time                 : 17 days, 4 hours, 12 minutes
    """.trimIndent()

    private fun respond(device: FakeDevice, prompt: String, body: Map<String, String>) {
        device.responder = { cmd ->
            val key = body.keys.firstOrNull { cmd.startsWith(it) }
            if (key != null) listOf("$prompt $cmd") + body.getValue(key).split('\n') + listOf(prompt)
            else listOf("$prompt $cmd", prompt)
        }
    }

    // -------------------------------------------------------- vendor conocido

    @Test
    fun `vendor detectado ejecuta SOLO su sonda y descubre vecinos`() = runBlocking {
        val device = FakeDevice() // buffer inicial: "Cisco IOS Software, Version 15.2"
        respond(
            device, FakeDevice.PROMPT,
            mapOf("show version" to iosShowVersion, "show cdp neighbors detail" to cdpDetail),
        )
        device.register("s1")

        val report = newService().fingerprint(com.opentermx.common.session.SessionId("s1"))

        assertEquals(listOf("match"), report.attempts.map { it.outcome }, "una sola sonda")
        assertEquals("cisco_show_version", report.attempts.single().probeId)
        assertEquals(Vendor.CISCO_IOS, report.identity.vendor)
        assertEquals("WS-C2960X-48TS-L", report.identity.model)
        assertEquals(Confidence.HIGH, report.identity.confidence)
        assertEquals("switch", report.roleSuggestion)
        assertEquals(1, report.neighbors.size)
        assertEquals("sw-acceso-01.empresa.local", report.neighbors.single().remoteHostname)
        assertFalse(report.neighborsTruncated)
        assertTrue(report.dryRun, "5A: dry-run por default hasta que 5B persista")
    }

    // -------------------------------------------------------- cadena a ciegas

    @Test
    fun `sin vendor previo la cadena sigue tras los no-match y para en el match (error 33)`() = runBlocking {
        val prompt = "switch#"
        val device = FakeDevice(
            initialBuffer = listOf("bienvenido al equipo de laboratorio", prompt),
            prompt = prompt,
        )
        respond(
            device, prompt,
            mapOf(
                "show version" to "% Invalid input detected at '^' marker.",
                "display version" to "% Unknown command.",
                "show system" to arubaShowSystem,
            ),
        )
        device.register("s2")

        val report = newService().fingerprint(com.opentermx.common.session.SessionId("s2"))

        assertEquals(
            listOf("no_match", "no_match", "match"),
            report.attempts.map { it.outcome },
            "los rechazos de CLI son no-match esperados, no errores",
        )
        assertEquals(Vendor.ARUBA_AOSCX, report.identity.vendor)
        assertEquals("sw-piso3-idf2", report.identity.hostname)
        assertEquals("switch", report.roleSuggestion)
        assertEquals(emptyList<Any?>(), report.neighbors, "Aruba sin comando de vecinos en 5A")
    }

    @Test
    fun `tres no-match dan UNKNOWN con confidence LOW`() = runBlocking {
        val prompt = "equipo-raro>"
        val device = FakeDevice(initialBuffer = listOf("sistema propietario v1", prompt), prompt = prompt)
        device.responder = { cmd -> listOf("$prompt $cmd", "Unknown action: $cmd", prompt) }
        device.register("s3")

        val report = newService().fingerprint(com.opentermx.common.session.SessionId("s3"))

        assertEquals(FingerprintService.MAX_CHAIN_ATTEMPTS, report.attempts.size, "la cadena se acota")
        assertTrue(report.attempts.all { it.outcome == "no_match" })
        assertEquals(Vendor.UNKNOWN, report.identity.vendor)
        assertEquals(Confidence.LOW, report.identity.confidence)
        assertEquals("unknown", report.roleSuggestion)
    }

    // -------------------------------------------------------- errores de sesión y gates

    @Test
    fun `sesion rota aborta la cadena con session_error en vez de seguir intentando`() = runBlocking {
        TestFixtures.registerSession(
            "s4", "SSH",
            buffer = listOf("Cisco IOS Software, Version 15.2", "router-1#"),
            sink = { false }, // la conexión no acepta comandos
        )

        val report = newService().fingerprint(com.opentermx.common.session.SessionId("s4"))

        assertEquals(listOf("session_error"), report.attempts.map { it.outcome })
        assertEquals(Vendor.CISCO_IOS, report.identity.vendor, "el vendor de la sesión se conserva")
        assertEquals(Confidence.LOW, report.identity.confidence)
        assertTrue(report.warnings.any { "sesión" in it })
    }

    @Test
    fun `sonda cuyo comando rechaza la whitelist queda blocked y NO toca el equipo`() = runBlocking {
        val device = FakeDevice()
        device.register("s5")
        val rejectAll = ReadOnlyCommandValidator(emptyMap(), emptyList())

        val report = newService(validator = rejectAll).fingerprint(com.opentermx.common.session.SessionId("s5"))

        assertTrue(report.attempts.all { it.outcome == "blocked" })
        assertTrue(device.received.isEmpty(), "ni des-paginación ni sonda: nada llegó al equipo")
        // El vendor de la sesión se conserva, pero sin sonda no hay identidad confiable.
        assertEquals(Vendor.CISCO_IOS, report.identity.vendor)
        assertEquals(Confidence.LOW, report.identity.confidence)
    }

    // -------------------------------------------------------- prueba activa de rol

    @Test
    fun `activeProbing opt-in resuelve rol con show spanning-tree summary`() = runBlocking {
        val device = FakeDevice()
        respond(
            device, FakeDevice.PROMPT,
            mapOf(
                // show version SIN modelo reconocible por las reglas => rol unknown
                "show version" to """
                    Cisco IOS Software, Modelo Rarisimo Software, Version 15.0(2)SE
                    equipo-x uptime is 1 week, 2 days
                    cisco RARO-9999 (PowerPC) processor (revision A0) with 65536K bytes of memory.
                """.trimIndent(),
                "show wlan summary" to "% Invalid input detected at '^' marker.",
                "show spanning-tree summary" to """
                    Switch is in pvst mode
                    Name                   Blocking Listening Learning Forwarding STP Active
                    ---------------------- -------- --------- -------- ---------- ----------
                    VLAN0001                     0         0        0          8          8
                    VLAN0010                     0         0        0          4          4
                """.trimIndent(),
                "show cdp neighbors detail" to "Total cdp entries displayed : 0",
            ),
        )
        device.register("s6")

        val withProbing = newService(activeProbing = true)
        val report = withProbing.fingerprint(com.opentermx.common.session.SessionId("s6"))

        assertEquals("switch", report.roleSuggestion)
        assertEquals("activeProbe:show spanning-tree summary", report.roleMatchedBy)

        // Sin opt-in, el mismo equipo queda unknown y NO se mandan comandos extra.
        TestFixtures.unregisterAll()
        val device2 = FakeDevice()
        respond(device2, FakeDevice.PROMPT, mapOf("show version" to "cisco RARO", "show cdp" to ""))
        device2.register("s7")
        newService(activeProbing = false).fingerprint(com.opentermx.common.session.SessionId("s7"))
        assertTrue(device2.received.none { it.startsWith("show wlan") || it.startsWith("show spanning-tree") })
    }
}
