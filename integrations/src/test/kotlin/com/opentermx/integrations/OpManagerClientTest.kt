package com.opentermx.integrations

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OpManagerClientTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun client(secret: String = "APIKEY-123") = OpManagerClient(
        MonitoringIntegration(
            kind = IntegrationKind.OPMANAGER,
            name = "opm-test",
            baseUrl = server.url("/").toString(),
            secretProvider = { secret },
        )
    )

    @Test
    fun `listAlarms mapea la estructura conocida y manda la apiKey como parametro`() {
        server.enqueue(
            MockResponse().setBody(
                """{"alarms":[{"deviceName":"core-sw-01","severity":"Critical","message":"Device down","modTime":"2026-06-10 21:00:00"}]}"""
            )
        )
        val result = client().listAlarms(deviceName = "core-sw-01", severity = "Critical", limit = 50)

        assertEquals(false, result["rawAvailable"])
        @Suppress("UNCHECKED_CAST")
        val alarms = result["alarms"] as List<Map<String, Any?>>
        assertEquals(1, alarms.size)
        assertEquals("core-sw-01", alarms.first()["device"])
        assertEquals("Critical", alarms.first()["severity"])
        assertEquals("Device down", alarms.first()["message"])

        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/api/json/alarm/listAlarms"))
        assertTrue(request.path!!.contains("apiKey=APIKEY-123"))
        assertTrue(request.path!!.contains("deviceName=core-sw-01"))
    }

    @Test
    fun `estructura desconocida devuelve el JSON crudo con rawAvailable=true en vez de romper`() {
        server.enqueue(MockResponse().setBody("""{"build":"127241","payload":{"x":1}}"""))
        val result = client().listAlarms(null, null, 10)
        assertEquals(true, result["rawAvailable"])
        assertNotNull(result["raw"], "el JSON original debe quedar accesible (error #27)")
    }

    @Test
    fun `error de la API no filtra la apiKey en el mensaje`() {
        server.enqueue(
            MockResponse().setBody("""{"error":{"code":401,"message":"Invalid apiKey APIKEY-123"}}""")
        )
        val ex = assertThrows(IntegrationException::class.java) {
            client().listAlarms(null, null, 10)
        }
        assertFalse(ex.message!!.contains("APIKEY-123"), "la key jamás viaja en errores: ${ex.message}")
    }

    @Test
    fun `5xx se reintenta con backoff y termina bien`() {
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("""{"alarms":[]}"""))

        val result = client().listAlarms(null, null, 10)
        assertEquals(false, result["rawAvailable"])
        assertEquals(3, server.requestCount, "2 reintentos tras los 5xx")
    }

    @Test
    fun `4xx NO se reintenta`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        assertThrows(IntegrationException::class.java) { client().listAlarms(null, null, 10) }
        assertEquals(1, server.requestCount, "un 4xx es del request: reintentar no lo arregla")
    }

    @Test
    fun `getPerformance tolerante con fallback raw`() {
        server.enqueue(MockResponse().setBody("""{"performanceData":[{"time":1,"value":42.5}]}"""))
        val ok = client().getPerformance("fw-01", "CPU", "last7days", null, null)
        assertEquals(false, ok["rawAvailable"])

        server.enqueue(MockResponse().setBody("""{"someBuildSpecificShape":{"a":1}}"""))
        val raw = client().getPerformance("fw-01", null, "today", null, null)
        assertEquals(true, raw["rawAvailable"])
        assertNotNull(raw["raw"])
    }
}
