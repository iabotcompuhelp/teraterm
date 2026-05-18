package com.opentermx.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.opentermx.mcp.handlers.ToolHandler
import com.opentermx.mcp.protocol.JsonRpcError
import com.opentermx.mcp.protocol.JsonRpcRequest
import com.opentermx.mcp.protocol.JsonRpcResponse
import com.opentermx.ai.audit.AiAuditLog
import com.opentermx.ai.safety.CredentialRedactor
import com.opentermx.common.ai.SessionChange
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.mcp.protocol.McpDispatcher
import com.opentermx.mcp.protocol.PromptProvider
import com.opentermx.mcp.protocol.ResourceProvider
import com.opentermx.mcp.protocol.TransportContext
import com.opentermx.mcp.security.RateLimiter
import com.opentermx.mcp.security.TailManager
import com.opentermx.common.event.ConnectionEvent
import com.opentermx.common.event.EventBus
import io.javalin.http.sse.SseClient
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
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
    private val serverVersion: String = BuildInfo.VERSION,
    private val verboseLog: Boolean = false,
    private val readOnly: Boolean = false,
    private val allowedSessionGlob: String? = null,
    private val rateLimitEnabled: Boolean = true,
    private val rateLimiter: RateLimiter = RateLimiter(),
    /**
     * Verificador de tokens custom. Para uso single-token, [start] sigue aceptando el
     * `token: String?` como antes — `verifyToken` lo wrapea. Para multi-token, el caller
     * pasa una lambda que matchea contra la lista de [com.opentermx.app.settings.McpTokenEntry].
     */
    private val tokenVerifier: ((String) -> Boolean)? = null,
    private val tlsConfig: TlsConfig? = null,
    private val auditLog: AiAuditLog? = null,
    private val redactor: CredentialRedactor = CredentialRedactor(),
    val tailManager: TailManager = TailManager(),
    private val resourceProvider: ResourceProvider = ResourceProvider.Empty,
    private val promptProvider: PromptProvider = PromptProvider.Default,
) {

    /**
     * Configuración TLS opcional: cuando viene no-null, el servidor escucha en HTTPS
     * usando el keystore (.jks o .p12) en [keyStorePath]. Una vez activado, el bind HTTP
     * se deshabilita — no exponemos ambos a la vez para evitar downgrade attacks.
     */
    data class TlsConfig(
        val keyStorePath: String,
        val keyStorePassword: String,
    )

    enum class Status { STOPPED, STARTING, RUNNING, STOPPING, FAILED }

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val dispatcher = McpDispatcher(
        handlers = handlers,
        serverName = serverName,
        serverVersion = serverVersion,
        readOnly = readOnly,
        allowedSessionGlob = allowedSessionGlob,
        resourceProvider = resourceProvider,
        promptProvider = promptProvider,
    )

    private val statusState = MutableStateFlow(Status.STOPPED)
    val status: StateFlow<Status> = statusState.asStateFlow()

    private val javalinRef = AtomicReference<Javalin?>(null)
    private val bindingRef = AtomicReference<Binding?>(null)
    private val lastErrorRef = AtomicReference<String?>(null)
    private val sseClients = CopyOnWriteArraySet<SseClient>()
    private val subscriptions = mutableListOf<AutoCloseable>()
    private var eventBusJob: Job? = null
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
            tlsConfig?.let { tls ->
                val sslPlugin = io.javalin.community.ssl.SslPlugin { ssl ->
                    ssl.keystoreFromPath(tls.keyStorePath, tls.keyStorePassword)
                    ssl.insecure = false  // sólo HTTPS, sin downgrade
                    ssl.secure = true
                    ssl.securePort = port
                    ssl.host = bindAddress
                }
                config.registerPlugin(sslPlugin)
            }
        }
        wireRoutes(app, token)

        try {
            if (tlsConfig != null) {
                // El plugin SSL ya configura el bind; `app.start()` sin args evita doble-bind.
                app.start()
            } else {
                app.start(bindAddress, port)
            }
            javalinRef.set(app)
            bindingRef.set(Binding(bindAddress, port, hasAuth = !token.isNullOrBlank()))
            subscribeNotifications()
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
        unsubscribeNotifications()
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
            if (verboseLog) logRequest(ctx, bodyText)
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
            val sessionKey = computeSessionKey(ctx)
            val toolName = extractToolName(request)
            if (rateLimitEnabled) {
                val decision = rateLimiter.allow(sessionKey, toolName)
                ctx.header("X-RateLimit-Remaining", decision.remaining.toString())
                ctx.header("X-RateLimit-Reset", decision.resetSeconds.toString())
                when (decision) {
                    is RateLimiter.Decision.Throttle -> {
                        respondTooManyRequests(ctx, request.id, decision.reason)
                        return@post
                    }
                    is RateLimiter.Decision.CircuitOpen -> {
                        respondCircuitOpen(ctx, request.id, decision.tool, decision.resetSeconds)
                        return@post
                    }
                    is RateLimiter.Decision.Allow -> { /* sigue */ }
                }
            }
            val transport = TransportContext(
                sessionKey = sessionKey,
                protocolVersionHeader = ctx.header("MCP-Protocol-Version"),
                enforceVersionHeader = true,
            )
            val response = dispatcher.handle(request, transport)
            if (response == null) {
                ctx.status(HttpStatus.NO_CONTENT)
                if (verboseLog) log.debug("MCP[verbose] ⇐ 204 No Content (notification)")
            } else {
                if (rateLimitEnabled && toolName == "propose_commands") {
                    val approved = isProposeCommandsApproved(response)
                    if (approved == false) rateLimiter.recordRejection(sessionKey, toolName)
                    else if (approved == true) rateLimiter.recordApproval(sessionKey, toolName)
                }
                val responseBody = mapper.writeValueAsString(response)
                ctx.contentType("application/json").result(responseBody)
                if (verboseLog) logResponse(200, responseBody)
            }
        }

        // Canal SSE para `notifications/*` server→cliente — sessions opened/closed y audit
        // entries. Cada mensaje es JSON-RPC notification (`{jsonrpc, method, params}`) en
        // un evento SSE con nombre `message` (default), alineado con el spec MCP.
        app.sse("/mcp/sse") { client ->
            client.keepAlive()
            sseClients += client
            client.sendEvent("ready", mapper.writeValueAsString(mapOf("server" to serverName, "version" to serverVersion)))
            client.onClose {
                sseClients -= client
                log.debug("Cliente SSE desconectado, restantes={}", sseClients.size)
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

    /**
     * Identifica a un cliente MCP de forma estable a través de requests. Usamos IP + un
     * prefijo del bearer token (los primeros 8 chars del hash) — suficiente para distinguir
     * clientes concurrentes desde la misma IP sin meter el token completo en memoria.
     */
    /**
     * Emite una notification JSON-RPC a todos los clientes SSE conectados. Robusto a
     * clientes desconectados — los `sendEvent` que fallen quitan al client del set.
     */
    fun emit(method: String, params: Any?) {
        if (sseClients.isEmpty()) return
        val payload = linkedMapOf<String, Any?>(
            "jsonrpc" to "2.0",
            "method" to method,
            "params" to params,
        )
        val body = mapper.writeValueAsString(payload)
        val dead = mutableListOf<SseClient>()
        for (client in sseClients) {
            runCatching { client.sendEvent("message", body) }
                .onFailure { dead += client }
        }
        sseClients.removeAll(dead)
    }

    private fun subscribeNotifications() {
        // Sessions opened/closed
        subscriptions += SessionRegistry.addChangeListener { change ->
            val action = when (change) {
                is SessionChange.Opened -> "opened"
                is SessionChange.Closed -> "closed"
            }
            emit(
                "notifications/sessions/changed",
                linkedMapOf(
                    "action" to action,
                    "sessionId" to change.id.value,
                    "metadata" to linkedMapOf(
                        "name" to change.metadata.name,
                        "protocol" to change.metadata.protocol,
                        "host" to change.metadata.host,
                        "port" to change.metadata.port,
                        "username" to change.metadata.username,
                    ),
                ),
            )
        }
        // Tail streams: subscribir al EventBus para DataReceived. Si la sesión tiene
        // tail activo, decodificar bytes y emitir `notifications/sessions/output`.
        eventBusJob = eventScope.launch {
            EventBus.events.filterIsInstance<ConnectionEvent.DataReceived>().collect { ev ->
                if (!tailManager.isActive(ev.sessionId)) return@collect
                val chunk = String(ev.data, 0, ev.length, Charsets.UTF_8)
                val lines = chunk.split('\n').filter { it.isNotEmpty() }
                if (lines.isNotEmpty()) {
                    emit(
                        "notifications/sessions/output",
                        linkedMapOf("sessionId" to ev.sessionId, "lines" to lines),
                    )
                }
            }
        }
        // Audit appended (con redacción aplicada)
        auditLog?.let { logger ->
            subscriptions += logger.addAppendListener { entry ->
                emit(
                    "notifications/audit/appended",
                    linkedMapOf(
                        "timestampMillis" to entry.timestampMillis,
                        "sessionId" to entry.sessionId,
                        "host" to entry.host,
                        "vendor" to entry.vendor,
                        "prompt" to redactor.redact(entry.prompt),
                        "commands" to redactor.redactLines(entry.commands),
                        "executedCount" to entry.executedCount,
                        "rejected" to entry.rejected,
                    ),
                )
            }
        }
    }

    private fun unsubscribeNotifications() {
        subscriptions.forEach { runCatching { it.close() } }
        subscriptions.clear()
        eventBusJob?.cancel()
        eventBusJob = null
        sseClients.clear()
    }

    private fun computeSessionKey(ctx: Context): String {
        val ip = ctx.ip()
        val auth = ctx.header("Authorization").orEmpty()
        val tokenHint = auth.removePrefix("Bearer ").take(8).ifBlank { "anon" }
        return "$ip:$tokenHint"
    }

    private fun enforceAuth(ctx: Context, token: String?) {
        val verifier = tokenVerifier
        // Si hay verifier custom, lo usamos exclusivamente (multi-token mode).
        // Si no, comparamos contra el token único legacy.
        val provided = ctx.header("Authorization").orEmpty().removePrefix("Bearer ").trim()
        val ok = when {
            verifier != null -> verifier(provided)
            token.isNullOrBlank() -> true
            else -> provided == token
        }
        if (!ok) {
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
        val body = mapper.writeValueAsString(JsonRpcResponse(id = id, error = JsonRpcError(code, message)))
        ctx.contentType("application/json").result(body)
        if (verboseLog) logResponse(HttpStatus.BAD_REQUEST.code, body)
    }

    private fun respondTooManyRequests(ctx: Context, id: Any?, reason: String) {
        ctx.status(429)
        val body = mapper.writeValueAsString(
            JsonRpcResponse(id = id, error = JsonRpcError(JsonRpcError.INTERNAL_ERROR, "Rate limit excedido: $reason"))
        )
        ctx.contentType("application/json").result(body)
    }

    private fun respondCircuitOpen(ctx: Context, id: Any?, tool: String, resetSec: Long) {
        ctx.status(429)
        val body = mapper.writeValueAsString(
            JsonRpcResponse(
                id = id,
                error = JsonRpcError(
                    JsonRpcError.INTERNAL_ERROR,
                    "Circuit open: demasiados rechazos de `$tool` (reset en ${resetSec}s)",
                ),
            )
        )
        ctx.contentType("application/json").result(body)
    }

    /** Saca el `name` del `tools/call` si la request lo trae. Null si no aplica. */
    private fun extractToolName(request: JsonRpcRequest): String? {
        if (request.method != "tools/call") return null
        val params = request.params as? Map<*, *> ?: return null
        return params["name"] as? String
    }

    /**
     * Inspecciona la respuesta de `propose_commands` para saber si el operador aprobó.
     * Devuelve `null` si la response no es del shape esperado (ej: error) para no
     * registrar nada en el circuit breaker.
     */
    private fun isProposeCommandsApproved(response: JsonRpcResponse): Boolean? {
        @Suppress("UNCHECKED_CAST")
        val result = response.result as? Map<String, Any?> ?: return null
        if (result["isError"] == true) return null
        @Suppress("UNCHECKED_CAST")
        val structured = result["structuredContent"] as? Map<String, Any?> ?: return null
        return structured["approved"] as? Boolean
    }

    /**
     * Loguea un request entrante para diagnóstico — el body se trunca a 2 KB para no
     * inundar el log si un cliente nos manda algo grande. El header Authorization se
     * preserva (es debug local, no producción ni logging remoto).
     */
    private fun logRequest(ctx: Context, body: String) {
        val headers = ctx.headerMap().entries.joinToString(", ") { (k, v) -> "$k=$v" }
        val safeBody = if (body.length > BODY_LOG_LIMIT) body.take(BODY_LOG_LIMIT) + "…(${body.length}b)" else body
        log.debug(
            "MCP[verbose] ⇒ {} {} headers=[{}] body={}",
            ctx.method(), ctx.path(), headers, safeBody,
        )
    }

    private fun logResponse(status: Int, body: String) {
        val safeBody = if (body.length > BODY_LOG_LIMIT) body.take(BODY_LOG_LIMIT) + "…(${body.length}b)" else body
        log.debug("MCP[verbose] ⇐ {} body={}", status, safeBody)
    }

    companion object {
        const val DEFAULT_PORT = 8765
        const val DEFAULT_BIND = "127.0.0.1"
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val BODY_LOG_LIMIT = 2048
    }
}