package com.opentermx.mcp.exec

import com.opentermx.ai.context.Vendor
import com.opentermx.mcp.handlers.FakeDevice
import com.opentermx.mcp.handlers.TestFixtures
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionCommandRunnerTest {

    private fun newRunner(
        rawSender: (com.opentermx.common.session.SessionId) -> ((ByteArray) -> Unit)? = { null },
    ) = SessionCommandRunner(pollIntervalMillis = 15, rawSender = rawSender)

    @AfterEach
    fun cleanup() = TestFixtures.unregisterAll()

    @Test
    fun `captura el output entre el eco y el prompt`() = runBlocking {
        val device = FakeDevice()
        val id = device.register("r1")
        val result = newRunner().run(id, Vendor.CISCO_IOS, "show version", timeoutMillis = 3000)

        assertFalse(result.timedOut)
        assertEquals(
            "output de [show version] linea 1\noutput de [show version] linea 2",
            result.output,
        )
        assertTrue(result.durationMs >= 0)
    }

    @Test
    fun `des-pagina una sola vez por sesion con el comando del vendor`() = runBlocking {
        val device = FakeDevice()
        val id = device.register("r1")
        val runner = newRunner()
        runner.run(id, Vendor.CISCO_IOS, "show version", timeoutMillis = 3000)
        runner.run(id, Vendor.CISCO_IOS, "show ip route", timeoutMillis = 3000)

        assertEquals(1, device.received.count { it == "terminal length 0" }, "des-paginación debe ser una sola vez")
        assertEquals("terminal length 0", device.received.first(), "des-paginación va antes del primer comando")
    }

    @Test
    fun `timeout devuelve output parcial con timedOut=true sin colgar`() = runBlocking {
        val device = FakeDevice()
        // Equipo que responde sin prompt final: el runner nunca ve fin de comando.
        device.responder = { cmd -> listOf("${FakeDevice.PROMPT} $cmd", "salida parcial...") }
        val id = device.register("r1")

        val result = newRunner().run(id, Vendor.CISCO_IOS, "show version", timeoutMillis = 600)

        assertTrue(result.timedOut)
        assertTrue(result.output.contains("salida parcial"), "output parcial esperado, vino: `${result.output}`")
    }

    @Test
    fun `paginador detectado responde espacio crudo y sigue capturando`() = runBlocking {
        val device = FakeDevice()
        device.responder = { cmd ->
            listOf("${FakeDevice.PROMPT} $cmd", "pagina 1", "--More--")
        }
        var spacesSent = 0
        val runner = newRunner(rawSender = { _ ->
            { bytes ->
                assertEquals(0x20, bytes.single().toInt(), "el paginador se responde con espacio")
                spacesSent++
                device.buffer.remove("--More--")
                device.buffer.addAll(listOf("pagina 2", FakeDevice.PROMPT))
            }
        })
        val id = device.register("r1")

        val result = runner.run(id, Vendor.CISCO_IOS, "show running-config", timeoutMillis = 3000)

        assertFalse(result.timedOut)
        assertEquals(1, spacesSent)
        assertTrue(result.output.contains("pagina 1") && result.output.contains("pagina 2"),
            "ambas páginas esperadas, vino: `${result.output}`")
        assertFalse(result.output.contains("More"))
    }

    @Test
    fun `mikrotik appendea without-paging al comando print`() = runBlocking {
        val device = FakeDevice(
            initialBuffer = listOf("MikroTik RouterOS 7.10", "[admin@mk] >"),
            prompt = "[admin@mk] >",
        )
        val id = device.register("mk1")
        newRunner().run(id, Vendor.MIKROTIK_ROUTEROS, "/interface print stats", timeoutMillis = 3000)

        assertEquals(listOf("/interface print stats without-paging"), device.received.toList(),
            "MikroTik no tiene comando de des-paginación; el flag va en el comando")
    }

    @Test
    fun `mutex por sesion serializa comandos concurrentes`() = runBlocking {
        val device = FakeDevice()
        device.responseDelayMillis = 150
        val id = device.register("r1")
        val runner = newRunner()

        val first = async { runner.run(id, Vendor.CISCO_IOS, "show version", timeoutMillis = 5000) }
        val second = async { runner.run(id, Vendor.CISCO_IOS, "show ip route", timeoutMillis = 5000) }
        val r1 = first.await()
        val r2 = second.await()

        assertFalse(r1.timedOut)
        assertFalse(r2.timedOut)
        // Cuando el segundo comando llegó al device, la respuesta del primero ya tenía
        // que estar completa en el buffer — eso es exactamente lo que el mutex garantiza.
        val orderedCommands = device.received.filterNot { it == "terminal length 0" }
        assertEquals(2, orderedCommands.size)
        val secondCmdIdx = device.received.indexOf(orderedCommands[1])
        val bufferWhenSecondArrived = device.bufferSizeAtReceive[secondCmdIdx]
        val firstResponseComplete = device.buffer.take(bufferWhenSecondArrived)
            .any { it == "output de [${orderedCommands[0]}] linea 2" }
        assertTrue(firstResponseComplete, "el segundo comando entró antes de que el primero terminara")
    }

    @Test
    fun `sesion sin sink lanza SessionGoneException`(): Unit = runBlocking {
        TestFixtures.registerSession(idValue = "dead", protocol = "SSH", sink = null)
        try {
            newRunner().run(
                com.opentermx.common.session.SessionId("dead"),
                Vendor.CISCO_IOS, "show version", timeoutMillis = 500,
            )
            assertTrue(false, "esperaba SessionGoneException")
        } catch (expected: SessionCommandRunner.SessionGoneException) {
            // ok
        }
    }
}
