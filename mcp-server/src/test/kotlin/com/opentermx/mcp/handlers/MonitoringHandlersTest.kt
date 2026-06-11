package com.opentermx.mcp.handlers

import com.opentermx.integrations.IntegrationKind
import com.opentermx.integrations.IntegrationRegistry
import com.opentermx.integrations.MonitoringIntegration
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MonitoringHandlersTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun registry(kind: IntegrationKind, name: String) = IntegrationRegistry { requested ->
        if (requested == name) MonitoringIntegration(
            kind = kind,
            name = name,
            baseUrl = server.url("/").toString(),
            secretProvider = { "TOKEN" },
        ) else null
    }

    @Test
    fun `integracion no configurada devuelve NOT_FOUND con mensaje accionable`() {
        val handler = ZabbixGetActiveProblemsHandler({ IntegrationRegistry.Empty })
        val ex = assertThrows(McpToolException::class.java) {
            runBlocking { handler.invoke(mapOf("integrationName" to "zbx-prod")) }
        }
        assertEquals(McpToolException.ErrorCode.NOT_FOUND, ex.code)
        assertTrue(ex.message!!.contains("monitoringIntegrations"))
    }

    @Test
    fun `tool de zabbix sobre una integracion opmanager es INVALID_ARGUMENT`() {
        val handler = ZabbixGetActiveProblemsHandler({ registry(IntegrationKind.OPMANAGER, "opm") })
        val ex = assertThrows(McpToolException::class.java) {
            runBlocking { handler.invoke(mapOf("integrationName" to "opm")) }
        }
        assertEquals(McpToolException.ErrorCode.INVALID_ARGUMENT, ex.code)
    }

    @Test
    fun `la respuesta marca contentOrigin como plataforma externa`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","result":"7.0.0","id":1}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","result":[{"eventid":"5","name":"Interface down","severity":"4"}],"id":2}"""
            )
        )
        // Nombre único por test: MonitoringSupport cachea clientes por nombre.
        val handler = ZabbixGetActiveProblemsHandler({ registry(IntegrationKind.ZABBIX, "zbx-origin-test") })
        val result = handler.invoke(mapOf("integrationName" to "zbx-origin-test", "minSeverity" to 3))

        assertEquals("external_monitoring_platform", result["contentOrigin"],
            "el cliente MCP debe poder tratar estos textos como NO confiables")
        assertEquals(false, result["truncated"])
        @Suppress("UNCHECKED_CAST")
        val data = result["data"] as Map<String, Any?>
        assertNotNull(data["problems"])
    }

    @Test
    fun `opmanager_get_alarms responde envuelto y con rawAvailable`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"alarms":[{"deviceName":"sw1","severity":"Trouble","message":"x"}]}"""))
        val handler = OpManagerGetAlarmsHandler({ registry(IntegrationKind.OPMANAGER, "opm-origin-test") })
        val result = handler.invoke(mapOf("integrationName" to "opm-origin-test", "limit" to 10))

        assertEquals("external_monitoring_platform", result["contentOrigin"])
        @Suppress("UNCHECKED_CAST")
        val data = result["data"] as Map<String, Any?>
        assertEquals(false, data["rawAvailable"])
    }
}
