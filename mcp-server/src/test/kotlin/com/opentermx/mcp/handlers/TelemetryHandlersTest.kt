package com.opentermx.mcp.handlers

import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.mcp.exec.SessionCommandRunner
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TelemetryHandlersTest {

    @TempDir
    lateinit var tmp: Path

    @AfterEach
    fun cleanup() = TestFixtures.unregisterAll()

    private fun newRunner() = SessionCommandRunner(pollIntervalMillis = 15, rawSender = { null })

    private fun auditLog() = AiAuditLog(tmp.resolve("audit.csv"))

    /** `show interfaces` IOS abreviado: Gi0/1 up con tasas, Gi0/2 admin down. */
    private val iosShowInterfaces = """
        GigabitEthernet0/1 is up, line protocol is up
          Hardware is Gigabit Ethernet, address is 0019.e87c.5a01 (bia 0019.e87c.5a01)
          Description: UPLINK-TO-CORE-SW01
          MTU 1500 bytes, BW 1000000 Kbit/sec, DLY 10 usec,
          Full-duplex, 1000Mb/s, media type is 10/100/1000BaseTX
          Input queue: 0/75/12/0 (size/max/drops/flushes); Total output drops: 35
          5 minute input rate 2304000 bits/sec, 412 packets/sec
          5 minute output rate 1841000 bits/sec, 389 packets/sec
             58422345 packets input, 4123456789 bytes, 0 no buffer
             142 input errors, 89 CRC, 0 frame, 0 overrun, 0 ignored
             49283746 packets output, 3987654321 bytes, 0 underruns
             0 output errors, 3 collisions, 1 interface resets
        GigabitEthernet0/2 is administratively down, line protocol is down
          Hardware is Gigabit Ethernet, address is 0019.e87c.5a02 (bia 0019.e87c.5a02)
          MTU 1500 bytes, BW 1000000 Kbit/sec, DLY 10 usec,
          Auto-duplex, Auto-speed, media type is 10/100/1000BaseTX
          Input queue: 0/75/0/0 (size/max/drops/flushes); Total output drops: 0
          5 minute input rate 0 bits/sec, 0 packets/sec
          5 minute output rate 0 bits/sec, 0 packets/sec
             0 packets input, 0 bytes, 0 no buffer
             0 input errors, 0 CRC, 0 frame, 0 overrun, 0 ignored
             0 packets output, 0 bytes, 0 underruns
             0 output errors, 0 collisions, 0 interface resets
    """.trimIndent()

    private fun ciscoDevice(): FakeDevice {
        val device = FakeDevice()
        device.responder = { cmd ->
            if (cmd.startsWith("show interfaces")) {
                listOf("${FakeDevice.PROMPT} $cmd") + iosShowInterfaces.split('\n') + listOf(FakeDevice.PROMPT)
            } else {
                listOf("${FakeDevice.PROMPT} $cmd", FakeDevice.PROMPT)
            }
        }
        return device
    }

    // ------------------------------------------------------------ get_interface_stats

    @Test
    fun `get_interface_stats parsea el output del vendor a JSON canonico`() = runBlocking {
        val device = ciscoDevice()
        device.register("s1")
        val handler = GetInterfaceStatsHandler(newRunner(), auditLog())

        val result = handler.invoke(mapOf("sessionId" to "s1"))

        assertEquals(true, result["parsed"])
        assertEquals("CISCO_IOS", result["vendor"])
        assertEquals("show interfaces", result["command"])
        assertEquals(false, result["timedOut"])
        assertEquals(false, result["persisted"])
        @Suppress("UNCHECKED_CAST")
        val interfaces = result["interfaces"] as List<Map<String, Any?>>
        assertEquals(2, interfaces.size)
        val gi01 = interfaces.first { it["name"] == "GigabitEthernet0/1" }
        assertEquals("UP", gi01["operStatus"])
        assertEquals(142L, gi01["inputErrors"])
        assertEquals(89L, gi01["crcErrors"])
        assertEquals(1_000_000_000L, gi01["speedBps"])
        val gi02 = interfaces.first { it["name"] == "GigabitEthernet0/2" }
        assertEquals("ADMIN_DOWN", gi02["operStatus"])
        assertNull(gi02["speedBps"], "Auto-speed no inventa la velocidad nominal")
    }

    @Test
    fun `get_interface_stats con interfaceName filtra y ajusta el comando`() = runBlocking {
        val device = ciscoDevice()
        device.register("s1")
        val handler = GetInterfaceStatsHandler(newRunner(), auditLog())

        val result = handler.invoke(mapOf("sessionId" to "s1", "interfaceName" to "GigabitEthernet0/1"))

        assertEquals("show interfaces GigabitEthernet0/1", result["command"])
        @Suppress("UNCHECKED_CAST")
        val interfaces = result["interfaces"] as List<Map<String, Any?>>
        assertEquals(listOf("GigabitEthernet0/1"), interfaces.map { it["name"] })
    }

    @Test
    fun `output irreconocible devuelve parsed=false con el crudo`() = runBlocking {
        val device = FakeDevice()
        device.responder = { cmd ->
            listOf("${FakeDevice.PROMPT} $cmd", "% Unrecognized command", FakeDevice.PROMPT)
        }
        device.register("s1")
        val handler = GetInterfaceStatsHandler(newRunner(), auditLog())

        val result = handler.invoke(mapOf("sessionId" to "s1"))

        assertEquals(false, result["parsed"])
        assertNotNull(result["rawOutput"], "ante Failure el LLM debe poder leer el crudo")
        assertEquals(emptyList<Any?>(), result["interfaces"])
    }

    @Test
    fun `vendor sin soporte de telemetria lanza INVALID_ARGUMENT`() {
        val device = FakeDevice(initialBuffer = emptyList()) // sin buffer => vendor UNKNOWN
        device.register("s1")
        val handler = GetInterfaceStatsHandler(newRunner(), auditLog())

        val ex = assertThrows(McpToolException::class.java) {
            runBlocking { handler.invoke(mapOf("sessionId" to "s1")) }
        }
        assertEquals(McpToolException.ErrorCode.INVALID_ARGUMENT, ex.code)
        assertTrue(device.received.isEmpty(), "sin vendor no se ejecuta nada")
    }

    // ------------------------------------------------------------ get_link_status

    @Test
    fun `get_link_status proyecta liviano y onlyProblems filtra admin-down`() = runBlocking {
        val device = ciscoDevice()
        device.register("s1")
        val handler = GetLinkStatusHandler(newRunner(), auditLog())

        val all = handler.invoke(mapOf("sessionId" to "s1"))
        @Suppress("UNCHECKED_CAST")
        val links = all["links"] as List<Map<String, Any?>>
        assertEquals(2, links.size)
        assertEquals(setOf("name", "adminStatus", "operStatus", "lastFlap"), links.first().keys)

        // Gi0/2 está admin down => NO es un "problema" (apagado a propósito).
        val problems = handler.invoke(mapOf("sessionId" to "s1", "onlyProblems" to true))
        @Suppress("UNCHECKED_CAST")
        val problemLinks = problems["links"] as List<Map<String, Any?>>
        assertEquals(emptyList<Map<String, Any?>>(), problemLinks)
    }

    // ------------------------------------------------------------ get_bandwidth_utilization

    @Test
    fun `bandwidth con tasas del equipo usa device_rate y calcula porcentaje`() = runBlocking {
        val device = ciscoDevice()
        device.register("s1")
        val handler = GetBandwidthUtilizationHandler(newRunner(), auditLog())

        val result = handler.invoke(mapOf("sessionId" to "s1"))

        assertEquals("device_rate", result["method"])
        @Suppress("UNCHECKED_CAST")
        val rows = result["interfaces"] as List<Map<String, Any?>>
        val gi01 = rows.first { it["name"] == "GigabitEthernet0/1" }
        assertEquals(2_304_000L, gi01["inputRateBps"])
        assertEquals(0.23, gi01["utilizationInPct"])
        assertEquals(0.18, gi01["utilizationOutPct"])
    }

    @Test
    fun `bandwidth sin tasas usa counter_delta y descarta deltas negativos`() = runBlocking {
        var call = 0
        val rxByCall = listOf(1_000_000L, 1_080_000L)   // +80_000 bytes
        val txByCall = listOf(2_000_000L, 1_500_000L)   // delta NEGATIVO => wrap/reset
        val device = FakeDevice(
            initialBuffer = listOf("MikroTik RouterOS 7.10", "[admin@mk] >"),
            prompt = "[admin@mk] >",
        )
        device.responder = { cmd ->
            val table = listOf(
                "[admin@mk] > $cmd",
                "Columns: NAME, RX-BYTE, TX-BYTE, RX-PACKET, TX-PACKET, RX-DROP, TX-DROP, TX-QUEUE-DROP, RX-ERROR, TX-ERROR",
                " #    NAME    RX-BYTE    TX-BYTE    RX-PACKET  TX-PACKET  RX-DROP  TX-DROP  TX-QUEUE-DROP  RX-ERROR  TX-ERROR",
                " 0 R  ether1  ${rxByCall[call]}  ${txByCall[call]}  100  200  0  0  0  0  0",
                "[admin@mk] >",
            )
            call++
            table
        }
        device.register("mk1")
        val handler = GetBandwidthUtilizationHandler(
            newRunner(), auditLog(),
            sampleDelayMillis = { 1L }, // no esperar 5 s reales en el test
        )

        val result = handler.invoke(mapOf("sessionId" to "mk1", "sampleIntervalSeconds" to 5))

        assertEquals("counter_delta", result["method"])
        @Suppress("UNCHECKED_CAST")
        val rows = result["interfaces"] as List<Map<String, Any?>>
        val ether1 = rows.first { it["name"] == "ether1" }
        // 80_000 bytes * 8 / 5 s = 128_000 bps
        assertEquals(128_000L, ether1["inputRateBps"])
        assertNull(ether1["outputRateBps"], "delta negativo (wrap) jamás produce tasa")
        assertNotNull(result["warnings"])
    }
}
