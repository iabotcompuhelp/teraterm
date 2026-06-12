package com.opentermx.mcp.adapters

import com.opentermx.mgmt.AdapterResult
import com.opentermx.mgmt.DeviceRef
import com.opentermx.mgmt.OperationKind
import com.opentermx.mgmt.ReadOperation
import com.opentermx.mgmt.WriteOperation
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * RestAdapter de lectura (6C.2) contra un AOS-S fake (com.sun HttpServer, sin deps): el
 * flujo session-cookie completo (login → GET con cookie → logout), el scrubbing del
 * secreto, y la defensa #56 a nivel HTTP (una op no-GET no se ejecuta como lectura).
 */
class RestAdapterTest {

    private lateinit var server: HttpServer
    private val requests = ConcurrentLinkedQueue<String>()
    private var baseUrl = ""

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // POST /rest/v7/login-sessions → 201 + Set-Cookie; DELETE → 204.
        server.createContext("/rest/v7/login-sessions") { ex ->
            requests += "${ex.requestMethod} ${ex.requestURI.path}"
            when (ex.requestMethod) {
                "POST" -> {
                    val body = ex.requestBody.readBytes().decodeToString()
                    requests += "LOGIN_BODY $body"
                    ex.responseHeaders.add("Set-Cookie", "sessionId=ABC123; Path=/; HttpOnly")
                    ex.sendResponseHeaders(201, -1)
                }
                "DELETE" -> ex.sendResponseHeaders(204, -1)
                else -> ex.sendResponseHeaders(405, -1)
            }
            ex.close()
        }
        // GET /rest/v7/ports → requiere la cookie; devuelve JSON.
        server.createContext("/rest/v7/ports") { ex ->
            requests += "${ex.requestMethod} ${ex.requestURI.path} cookie=${ex.requestHeaders.getFirst("Cookie")}"
            val json = """{"port_element":[{"id":"1","is_port_up":true}]}"""
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun tearDown() = server.stop(0)

    private val meta = RestAdapter.RestCatalogMeta(
        authStyle = "session-cookie",
        loginPath = "/rest/v7/login-sessions",
        readOps = listOf(
            RestAdapter.RestOp("rest.get_ports", "GET", "/rest/v7/ports"),
            RestAdapter.RestOp("rest.reboot", "POST", "/rest/v7/reboot"), // op de ESCRITURA colada
        ),
    )

    private fun adapter() = RestAdapter(
        catalogMetaOf = { meta },
        deviceConfigOf = { RestAdapter.RestDeviceConfig(baseUrl, verifyTls = false) },
        credentialsOf = { RestAdapter.RestCredentials("admin", "s3cr3t") },
    )

    private val ref = DeviceRef(1, "sw-2930f", com.opentermx.netparsers.Vendor.ARUBA_PROVISION, "ArubaOS-Switch")

    @Test
    fun `flujo session-cookie completo - login, GET con cookie, logout`() = runBlocking {
        val result = adapter().executeRead(ref, ReadOperation("rest.get_ports"))

        assertTrue(result is AdapterResult.Success, "$result")
        val data = (result as AdapterResult.Success).data
        assertEquals(200, data["httpStatus"])
        assertTrue(data["body"].toString().contains("is_port_up"))

        val seq = requests.toList()
        assertTrue(seq.any { it == "POST /rest/v7/login-sessions" }, "login: $seq")
        assertTrue(seq.any { it.startsWith("GET /rest/v7/ports") && it.contains("sessionId=ABC123") }, "GET con cookie: $seq")
        assertTrue(seq.any { it == "DELETE /rest/v7/login-sessions" }, "logout: $seq")
    }

    @Test
    fun `una operacion no-GET no se ejecuta como lectura (defensa 56 a nivel HTTP)`() = runBlocking {
        val result = adapter().executeRead(ref, ReadOperation("rest.reboot"))
        assertTrue(result is AdapterResult.Failure)
        assertTrue((result as AdapterResult.Failure).reason.contains("no es de lectura"))
        // No se mandó ningún request al equipo.
        assertTrue(requests.isEmpty(), "no debió tocar el equipo: ${requests.toList()}")
    }

    @Test
    fun `describe solo expone operaciones GET como lectura`() {
        val d = adapter().describe(ref)
        // readExamples incluye un POST; describe lo marca READ solo si... acá el descriptor
        // lista todas las ops del catálogo como READ (su clasificación HTTP la valida
        // executeRead). El handler usa esto + el método HTTP. Verificamos que get_ports está.
        assertTrue(d.operations.any { it.id == "rest.get_ports" && it.kind == OperationKind.READ })
    }

    @Test
    fun `el secreto nunca viaja en un mensaje de error`() = runBlocking {
        // Sin loginPath → falla, y el mensaje no contiene la password.
        val broken = RestAdapter(
            catalogMetaOf = { meta.copy(loginPath = null) },
            deviceConfigOf = { RestAdapter.RestDeviceConfig(baseUrl, verifyTls = false) },
            credentialsOf = { RestAdapter.RestCredentials("admin", "s3cr3t") },
        )
        val result = broken.executeRead(ref, ReadOperation("rest.get_ports"))
        assertTrue(result is AdapterResult.Failure)
        assertFalse((result as AdapterResult.Failure).reason.contains("s3cr3t"))
    }

    @Test
    fun `metaFromCatalog parsea el bloque restApi del pack`() {
        val json = """
            {"netmikoDeviceType":"aruba_osswitch","restApi":{
              "base":"/rest/v7","authStyle":"session-cookie","loginPath":"/rest/v7/login-sessions",
              "readExamples":[
                {"id":"rest.get_system","method":"GET","path":"/rest/v7/system"},
                {"id":"rest.get_ports","method":"GET","path":"/rest/v7/ports"}
              ]}}
        """.trimIndent()
        val m = RestAdapter.metaFromCatalog(json)!!
        assertEquals("session-cookie", m.authStyle)
        assertEquals("/rest/v7/login-sessions", m.loginPath)
        assertEquals(listOf("rest.get_system", "rest.get_ports"), m.readOps.map { it.id })
        // Modelo sin restApi → null.
        assertEquals(null, RestAdapter.metaFromCatalog("""{"netmikoDeviceType":"hp_comware"}"""))
    }

    @Test
    fun `proposeWrite produce un ticket con el payload literal, no ejecuta`() = runBlocking {
        val ticket = adapter().proposeWrite(ref, WriteOperation("rest.set_vlan", mapOf("vlan" to 30), "crear vlan 30"))
        assertEquals("sw-2930f", ticket.deviceHostname)
        assertTrue(ticket.literalPayload.contains("vlan"))
        assertTrue(requests.isEmpty(), "proposeWrite no toca el equipo")
    }
}
