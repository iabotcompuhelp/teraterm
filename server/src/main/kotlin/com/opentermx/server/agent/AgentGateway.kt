package com.opentermx.server.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.opentermx.agent.AgentHeartbeat
import com.opentermx.agent.AgentHeartbeatAck
import io.javalin.Javalin
import java.security.MessageDigest

class AgentGateway(
    private val bindAddress: String,
    private val port: Int,
    private val token: String,
    private val registry: AgentRegistry,
) : AutoCloseable {
    private val mapper = jacksonObjectMapper()
    private var app: Javalin? = null

    fun start() {
        check(app == null) { "Agent gateway ya está iniciado" }
        app = Javalin.create { config ->
            config.showJavalinBanner = false
            config.http.maxRequestSize = 1_048_576
        }.post("/agent/v1/heartbeat") { ctx ->
            if (!authorized(ctx.header("Authorization"))) {
                ctx.status(401).json(mapOf("error" to "unauthorized"))
                return@post
            }
            try {
                registry.update(mapper.readValue<AgentHeartbeat>(ctx.body()))
                ctx.json(AgentHeartbeatAck(true, System.currentTimeMillis(), 5))
            } catch (e: IllegalArgumentException) {
                ctx.status(400).json(mapOf("error" to (e.message ?: "invalid heartbeat")))
            }
        }.get("/agent/v1/health") { ctx ->
            ctx.json(mapOf("status" to "ok", "activeSessions" to registry.sessions().size))
        }.start(bindAddress, port)
    }

    private fun authorized(header: String?): Boolean {
        val supplied = header?.removePrefix("Bearer ") ?: return false
        return MessageDigest.isEqual(token.toByteArray(), supplied.toByteArray())
    }

    override fun close() {
        app?.stop()
        app = null
    }
}
