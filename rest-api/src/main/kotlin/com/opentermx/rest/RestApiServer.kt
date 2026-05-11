package com.opentermx.rest

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import org.slf4j.LoggerFactory

/**
 * Servidor HTTP embebido (spec v4 § API REST interna / Webhook local).
 *
 * Endpoints — todos requieren `Authorization: Bearer <token>`:
 *   POST   /api/macro/execute       — body [ExecuteMacroRequest]
 *   POST   /api/terminal/send       — body {sessionId, text}
 *   GET    /api/terminal/buffer     — query: sessionId, lines (default 50)
 *   GET    /api/sessions
 *   POST   /api/tftp/start          — body [TftpStartRequest]
 *   POST   /api/tftp/stop
 *   GET    /api/tftp/status
 *   GET    /api/health              — sin auth, devuelve {status:"ok"}
 *
 * Bind seguro por defecto: `127.0.0.1`. Para exponer fuera de localhost configurar
 * explícitamente `0.0.0.0` desde el diálogo Setup → REST API.
 */
class RestApiServer(
    private val hooks: RestApiHooks,
    private val config: RestApiConfig,
    private val auditLog: RestApiLog = RestApiLog(null),
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper().registerKotlinModule().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
    private var app: Javalin? = null

    @get:JvmName("isRunning")
    val running: Boolean get() = app != null

    val effectivePort: Int? get() = app?.port()

    fun start() {
        if (app != null) return
        val javalin = Javalin.create { cfg ->
            cfg.showJavalinBanner = false
            cfg.useVirtualThreads = false
            cfg.jetty.defaultHost = config.bindHost
        }
        javalin.before(::authFilter)
        javalin.after(::recordRequest)
        registerRoutes(javalin)
        javalin.exception(Exception::class.java) { e, ctx ->
            log.warn("REST handler error: {}", e.message)
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).result("""{"error":"${e.message?.replace("\"", "'")}"}""")
                .contentType("application/json")
        }
        javalin.start(config.bindHost, config.port)
        app = javalin
        log.info("REST API listening on http://{}:{}", config.bindHost, javalin.port())
    }

    fun stop() {
        app?.stop()
        app = null
    }

    private fun registerRoutes(jav: Javalin) {
        jav.get("/api/health") { it.json(mapOf("status" to "ok", "running" to true)) }

        jav.get("/api/sessions") { ctx ->
            ctx.json(mapOf("sessions" to hooks.listSessions()))
        }

        jav.get("/api/terminal/buffer") { ctx ->
            val sessionId = ctx.queryParam("sessionId")
            if (sessionId == null) {
                ctx.status(HttpStatus.BAD_REQUEST).json(error("sessionId requerido"))
                return@get
            }
            val lines = ctx.queryParam("lines")?.toIntOrNull() ?: 50
            ctx.json(hooks.terminalBuffer(sessionId, lines))
        }

        jav.post("/api/terminal/send") { ctx ->
            val body = readBody<Map<String, Any?>>(ctx) ?: return@post
            val sessionId = body["sessionId"] as? String
            if (sessionId == null) {
                ctx.status(HttpStatus.BAD_REQUEST).json(error("sessionId requerido"))
                return@post
            }
            val text = body["text"] as? String
            if (text == null) {
                ctx.status(HttpStatus.BAD_REQUEST).json(error("text requerido"))
                return@post
            }
            ctx.json(hooks.sendToTerminal(sessionId, text))
        }

        jav.post("/api/macro/execute") { ctx ->
            val req = readBody<ExecuteMacroRequest>(ctx) ?: return@post
            ctx.json(hooks.executeMacro(req))
        }

        jav.post("/api/tftp/start") { ctx ->
            val req = readBody<TftpStartRequest>(ctx) ?: return@post
            ctx.json(hooks.startTftpServer(req))
        }

        jav.post("/api/tftp/stop") { ctx -> ctx.json(hooks.stopTftpServer()) }
        jav.get("/api/tftp/status") { ctx -> ctx.json(hooks.tftpStatus()) }
    }

    private inline fun <reified T> readBody(ctx: Context): T? {
        return try {
            mapper.readValue(ctx.body(), T::class.java)
        } catch (e: Exception) {
            ctx.status(HttpStatus.BAD_REQUEST).json(error("Body inválido: ${e.message}"))
            null
        }
    }

    private fun authFilter(ctx: Context) {
        if (ctx.path() == "/api/health") return
        if (!config.requireAuth) return
        val header = ctx.header("Authorization").orEmpty()
        val token = header.removePrefix("Bearer ").trim()
        if (token.isEmpty() || token != config.token) {
            ctx.status(HttpStatus.UNAUTHORIZED).json(error("Token inválido o ausente"))
            ctx.skipRemainingHandlers()
        }
    }

    private fun recordRequest(ctx: Context) {
        val started = ctx.attribute<Long>("startedAt") ?: System.currentTimeMillis()
        val durationMs = System.currentTimeMillis() - started
        auditLog.record(
            ApiLogEntry(
                timestampMillis = System.currentTimeMillis(),
                method = ctx.method().name,
                path = ctx.path(),
                status = ctx.status().code,
                durationMs = durationMs,
                remoteIp = ctx.ip(),
                sessionId = ctx.queryParam("sessionId") ?: ctx.attribute<String>("sessionId"),
            )
        )
    }

    private fun error(message: String) = mapOf("status" to "error", "error" to message)
}

data class RestApiConfig(
    val bindHost: String,
    val port: Int,
    val token: String,
    val requireAuth: Boolean = true,
)
