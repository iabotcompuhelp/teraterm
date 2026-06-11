package com.opentermx.integrations

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ZabbixClientTest {

    private lateinit var server: MockWebServer
    private val mapper = ObjectMapper()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun client(secret: String, extra: Map<String, String> = emptyMap()) = ZabbixClient(
        MonitoringIntegration(
            kind = IntegrationKind.ZABBIX,
            name = "zbx-test",
            baseUrl = server.url("/").toString(),
            extra = extra,
            secretProvider = { secret },
        )
    )

    private fun rpcResult(result: Any) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(mapper.writeValueAsString(mapOf("jsonrpc" to "2.0", "result" to result, "id" to 1)))

    // ------------------------------------------------- modos de auth (criterio de aceptación)

    @Test
    fun `version 7 usa header Bearer y NO manda el campo auth en el body`() {
        server.enqueue(rpcResult("7.0.0"))                       // apiinfo.version
        server.enqueue(rpcResult(listOf(mapOf("hostid" to "10084", "host" to "router1"))))
        server.enqueue(rpcResult(listOf(mapOf("itemid" to "111", "value_type" to 3, "key_" to "net.if.in[Gi0/1]", "units" to "bps"))))
        server.enqueue(rpcResult(listOf(mapOf("clock" to "1750000000", "value" to "12345"))))

        val result = client("TOKEN-BEARER-XYZ").getHistory(
            "router1", "net.if.in", "2026-06-09T00:00:00Z", "2026-06-10T00:00:00Z", 100,
        )
        assertEquals("history", result["source"])

        server.takeRequest() // apiinfo.version: sin auth — correcto en 7.x
        val hostGet = server.takeRequest()
        assertEquals("Bearer TOKEN-BEARER-XYZ", hostGet.getHeader("Authorization"))
        val hostBody = mapper.readTree(hostGet.body.readUtf8())
        assertNull(hostBody["auth"], "en 7.x el campo auth en el body produce error: no debe ir")
    }

    @Test
    fun `version 6_0 usa el campo auth legacy con el API token`() {
        server.enqueue(rpcResult("6.0.21"))                      // apiinfo.version
        server.enqueue(rpcResult(listOf(mapOf("hostid" to "1", "host" to "fw1"))))
        server.enqueue(rpcResult(listOf(mapOf("itemid" to "9", "value_type" to 0, "key_" to "cpu", "units" to "%"))))
        server.enqueue(rpcResult(emptyList<Any>()))

        client("LEGACY-API-TOKEN").getHistory("fw1", "cpu", "2026-06-09T00:00:00Z", null, 100)

        server.takeRequest() // apiinfo.version
        val hostGet = server.takeRequest()
        assertNull(hostGet.getHeader("Authorization"), "en 5.x-6.0 no va header Bearer")
        assertEquals("LEGACY-API-TOKEN", mapper.readTree(hostGet.body.readUtf8())["auth"].asText())
    }

    @Test
    fun `modo legacy con usuario-password hace user_login una vez y cachea la sesion`() {
        server.enqueue(rpcResult("5.0.40"))                      // apiinfo.version
        server.enqueue(rpcResult("SESSION-TOKEN-123"))           // user.login
        server.enqueue(rpcResult(listOf(mapOf("eventid" to "1", "name" to "High CPU", "severity" to "4"))))
        server.enqueue(rpcResult(emptyList<Any>()))              // segunda llamada reutiliza sesión

        val zbx = client("Admin:zabbix")
        zbx.getActiveProblems(null, 0, 10)
        zbx.getActiveProblems(null, 0, 10)

        server.takeRequest() // apiinfo.version
        val login = server.takeRequest()
        val loginBody = mapper.readTree(login.body.readUtf8())
        assertEquals("user.login", loginBody["method"].asText())
        assertEquals("Admin", loginBody["params"]["username"].asText())
        val problems1 = server.takeRequest()
        assertEquals("SESSION-TOKEN-123", mapper.readTree(problems1.body.readUtf8())["auth"].asText())
        val problems2 = mapper.readTree(server.takeRequest().body.readUtf8())
        assertEquals("problem.get", problems2["method"].asText(), "la sesión se cachea: no hay segundo user.login")
        assertEquals("SESSION-TOKEN-123", problems2["auth"].asText())
        assertEquals(4, server.requestCount, "version + login + 2×problem.get — ni un request más")
    }

    // ------------------------------------------------- value_type / epoch / trend

    @Test
    fun `history_get usa el value_type resuelto por item_get y epoch en segundos`() {
        server.enqueue(rpcResult("7.0.0"))
        server.enqueue(rpcResult(listOf(mapOf("hostid" to "1", "host" to "r1"))))
        server.enqueue(rpcResult(listOf(mapOf("itemid" to "42", "value_type" to 3, "key_" to "net.if.in", "units" to "bps"))))
        server.enqueue(rpcResult(emptyList<Any>()))

        client("T").getHistory("r1", "net.if.in", "2026-06-09T00:00:00Z", "2026-06-10T00:00:00Z", 500)

        repeat(3) { server.takeRequest() }
        val history = mapper.readTree(server.takeRequest().body.readUtf8())
        assertEquals("history.get", history["method"].asText())
        assertEquals(3, history["params"]["history"].asInt(), "history = value_type del item (error #23)")
        val timeFrom = history["params"]["time_from"].asLong()
        assertEquals(
            java.time.OffsetDateTime.parse("2026-06-09T00:00:00Z").toEpochSecond(), timeFrom,
            "epoch en SEGUNDOS, no milisegundos (error #24)",
        )
        assertTrue(timeFrom < 10_000_000_000L, "magnitud de segundos, no de milisegundos")
        assertEquals("clock", history["params"]["sortfield"].asText())
        assertEquals(500, history["params"]["limit"].asInt())
    }

    @Test
    fun `rangos mayores a 7 dias van a trend_get`() {
        server.enqueue(rpcResult("7.0.0"))
        server.enqueue(rpcResult(listOf(mapOf("hostid" to "1", "host" to "r1"))))
        server.enqueue(rpcResult(listOf(mapOf("itemid" to "42", "value_type" to 3, "key_" to "k", "units" to ""))))
        server.enqueue(rpcResult(emptyList<Any>()))

        val result = client("T").getHistory(
            "r1", "k", "2026-05-01T00:00:00Z", "2026-06-01T00:00:00Z", 100,
        )

        assertEquals("trend", result["source"])
        repeat(3) { server.takeRequest() }
        val trend = mapper.readTree(server.takeRequest().body.readUtf8())
        assertEquals("trend.get", trend["method"].asText())
        assertNull(trend["params"]["history"], "trend.get no lleva el parámetro history")
    }

    @Test
    fun `error de la API se propaga sin filtrar el token`() {
        server.enqueue(rpcResult("7.0.0"))
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","error":{"code":-32602,"message":"Invalid params.","data":"token SUPER-SECRETO rechazado"},"id":1}"""
            )
        )
        val ex = assertThrows(IntegrationException::class.java) {
            client("SUPER-SECRETO").getActiveProblems(null, 0, 10)
        }
        assertFalse(ex.message!!.contains("SUPER-SECRETO"), "el token jamás viaja en errores: ${ex.message}")
        assertTrue(ex.message!!.contains("***"))
    }
}
