package com.opentermx.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.opentermx.mcp.handlers.ToolHandler
import com.opentermx.mcp.protocol.JsonRpcError
import com.opentermx.mcp.protocol.JsonRpcRequest
import com.opentermx.mcp.protocol.JsonRpcResponse
import com.opentermx.mcp.protocol.McpDispatcher
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * Servidor MCP HTTP/SSE de OpenTermX. Expone las tools registradas en [handlers] sobre
 * un único endpoint `POST /mcp` (JSON-RPC 2.0) y un canal SSE complementario en `GET /mcp/sse`
 * para notificaciones server→cliente.
 *
 * Seguridad — invariante del módulo:
 *  - Bind por defecto a `127.0.0.1`. Bind a `0.0.0.0` es legal (un operador puede
 *    saber lo que hace) pero el caller — la UI de Setup — muestra warning.
 *  - Si el constructor recibe `token != null`, todo request debe traer
 *    `Authorization: Bearer <token>`. Sin token → 401.
 *  - Las tools mutativas pasan por su `ApprovalGate`; esto vive dentro del handler,
 *    no del transporte.
 *
 * Ciclo de vida observable: [status] es un `StateFlow` que la UI bindea para mostrar
 * en la status bar. Estados: `STOPPED → STARTING → RUNNING → STOPPING → STOPPED`,
 * con transición a `FAILED` si Javalin no levanta.
 */
class McpServer(
    private val handlers: List<ToolHandler>,
    private val serverName: String = "opentermx-mcp",
    private val serverVersion: String = "0.1.0",
) {

    enum class Status { STOPPED, STARTING, RUNNING, STOPPING, FAILED }

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val dispatcher = McpDispatcher(handlers, serverName, serverVersion)

    private val statusState = MutableStateFlow(Status.STOPPED)
    val status: StateFlow<Status> = statusState.asStateFlow()

    private val javalinRef = AtomicReference<Javalin?>(null)
    private val bindingRef = AtomicReference<Binding?>(null)
    private val lastErrorRef = AtomicReference<String?>(null)

    data class Binding(val host: String, val port: Int, val hasAuth: Boolean)

    /** Devuelve la dirección efectiva si el servidor está RUNNING, `null` si no. */
    fun binding(): Binding? = bindingRef.get()

    /** Último mensaje de error si la última transición falló — útil para el tooltip de la status bar. */
    fun lastError(): String? = lastErrorRef.get()

    /**
     * Arranca el servidor en [bindAddress]:[port]. Idempotente: si ya está RUNNING, no
     * hace nada (loguea aviso). Si está STARTING, espera hasta resolución.
     *
     * @throws IllegalStateException si el servidor falla al levantar — útil para que la UI
     *   muestre el error sin que se trague.
     */
    @Synchronized
    fun start(port: Int, bindAddress: String = "127.0.0.1", token: String? = null) {
        when (statusState.value) {
            Status.RUNNING, Status.STARTING -> {
                log.info("MCP server ya está en estado {}, ignoro start", statusState.value)
                return
            }
            else -> {}
        }
        require(port in 1..65535) { "Puerto fuera de rango: $port" }
        require(bindAddress.isNotBlank()) { "Bind address vacío" }

        statusState.value = Status.STARTING
        lastErrorRef.set(null)

        val app = Javalin.create { config ->
            config.showJavalinBanner = false
            config.http.defaultContentType = "application/json"
        }
        wireRoutes(app, token)

        try {
            app.start(bindAddress, port)
            javalinRef.set(app)
            bindingRef.set(Binding(bindAddress, port, hasAuth = !token.isNullOrBlank()))
            statusState.value = Status.RUNNING
            log.info(
                "MCP server arriba en {}:{} con auth={} y {} tools",
                bindAddress, port, !token.isNullOrBlank(), handlers.size,
            )
        } catch (e: Throwable) {
            log.error("MCP server no pudo arrancar en {}:{}: {}", bindAddress, port, e.message, e)
            runCatching { app.stop() }
            javalinRef.set(null)
            bindingRef.set(null)
            lastErrorRef.set(e.message ?: e.javaClass.simpleName)
            statusState.value = Status.FAILED
            throw IllegalStateException("No se pudo iniciar el servidor MCP: ${e.message}", e)
        }
    }

    /**
     * Para el servidor con timeout de [timeoutMillis] ms. Pasa a STOPPED al terminar.
     * Llamar desde shutdown hook o desde el botón Stop de la UI.
     */
    @Synchronized
    fun stop(timeoutMillis: Long = STOP_TIMEOUT_MILLIS) {
        val app = javalinRef.get() ?: run {
            statusState.value = Status.STOPPED
            return
        }
        statusState.value = Status.STOPPING
        val thread = Thread({
            runCatching { app.stop() }.onFailure { e ->
                log.warn("Error al detener Javalin (ignorado): {}", e.message)
            }
        }, "mcp-stop")
        thread.isDaemon = true
        thread.start()
        thread.join(timeoutMillis)
        if (thread.isAlive) {
            log.warn("Stop de MCP server no respondió en {} ms — forzando estado STOPPED", timeoutMillis)
        }
        javalinRef.set(null)
        bindingRef.set(null)
        statusState.value = Status.STOPPED
    }

    private fun wireRoutes(app: Javalin, token: String?) {
        // Auth global: si hay token, todo endpoint del servidor MCP lo requiere
        // (incluyendo /mcp/health — útil para no filtrar metadata desde la LAN).
        app.before { ctx -> enforceAuth(ctx, token) }

        app.post("/mcp") { ctx ->
            val bodyText = ctx.body()
            if (bodyText.isBlank()) {
                respondError(ctx, null, JsonRpcError.INVALID_REQUEST, "Body vacío")
                return@post
            }
            val request = runCatching {
                mapper.readValue(bodyText, JsonRpcRequest::class.java)
            }.getOrElse {
                respondError(ctx, null, JsonRpcError.PARSE_ERROR, "JSON inválido: ${it.message}")
                return@post
            }
            val response = dispatcher.handle(request)
            if (response == null) {
                // Era una notification — protocolo: respondemos 204 vacío.
                ctx.status(HttpStatus.NO_CONTENT)
            } else {
                ctx.contentType("application/json").result(mapper.writeValueAsString(response))
            }
        }

        // Canal SSE mínimo: el servidor MCP de OpenTermX no emite todavía notifications
        // server→cliente (no hay change-streams de sesiones aún), pero mantenemos abierto
        // el endpoint para compatibilidad con clientes que se quedan esperando uno.
        app.sse("/mcp/sse") { client ->
            client.keepAlive()
            client.sendEvent("ready", mapper.writeValueAsString(mapOf("server" to serverName, "version" to serverVersion)))
            client.onClose {
                log.debug("Cliente SSE desconectado")
            }
        }

        app.get("/mcp/health") { ctx ->
            ctx.json(
                mapOf(
                    "status" to statusState.value.name,
                    "tools" to handlers.size,
                    "server" to serverName,
                    "version" to serverVersion,
                )
            )
        }
    }

    private fun enforceAuth(ctx: Context, token: String?) {
        if (token.isNullOrBlank()) return
        val header = ctx.header("Authorization").orEmpty()
        val expected = "Bearer $token"
        if (header != expected) {
            ctx.status(HttpStatus.UNAUTHORIZED)
            ctx.contentType("application/json").result(
                mapper.writeValueAsString(
                    JsonRpcResponse(error = JsonRpcError(JsonRpcError.INVALID_REQUEST, "Token inválido o ausente"))
                )
            )
            ctx.skipRemainingHandlers()
        }
    }

    private fun respondError(ctx: Context, id: Any?, code: Int, message: String) {
        ctx.status(HttpStatus.BAD_REQUEST)
        ctx.contentType("application/json").result(
            mapper.writeValueAsString(JsonRpcResponse(id = id, error = JsonRpcError(code, message)))
        )
    }

    companion object {
        const val DEFAULT_PORT = 8765
        const val DEFAULT_BIND = "127.0.0.1"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}