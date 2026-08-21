package com.opentermx.server.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.opentermx.agent.AgentHeartbeat
import com.opentermx.agent.AgentHeartbeatAck
import com.opentermx.agent.RemoteTaskResult
import io.javalin.Javalin
import java.security.MessageDigest
import com.opentermx.mcp.operation.OperationEventType
import com.opentermx.mcp.operation.OperationRegistry

class AgentGateway(
    private val bindAddress: String,
    private val port: Int,
    private val token: String,
    private val registry: AgentRegistry,
    private val taskStore: RemoteTaskStore,
    private val operations: OperationRegistry? = null,
    private val deviceContexts: FederatedDeviceContextStore? = null,
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
                val heartbeat = mapper.readValue<AgentHeartbeat>(ctx.body())
                if (ctx.header(AGENT_ID_HEADER) != heartbeat.agentId) {
                    ctx.status(403).json(mapOf("error" to "agent identity mismatch"))
                    return@post
                }
                registry.update(heartbeat)
                ctx.json(AgentHeartbeatAck(true, System.currentTimeMillis(), 5))
            } catch (e: IllegalArgumentException) {
                ctx.status(400).json(mapOf("error" to (e.message ?: "invalid heartbeat")))
            }
        }.get("/agent/v1/health") { ctx ->
            ctx.json(mapOf("status" to "ok", "activeSessions" to registry.sessions().size))
        }.get("/agent/v1/tasks/next") { ctx ->
            if (!authorized(ctx.header("Authorization"))) {
                ctx.status(401); return@get
            }
            val agentId = ctx.queryParam("agentId").orEmpty()
            if (agentId.isBlank() || ctx.header(AGENT_ID_HEADER) != agentId) {
                ctx.status(403); return@get
            }
            val task = taskStore.claimNext(agentId)
            if (task == null) ctx.status(204) else ctx.json(task)
        }.post("/agent/v1/tasks/{taskId}/result") { ctx ->
            if (!authorized(ctx.header("Authorization"))) {
                ctx.status(401); return@post
            }
            val agentId = ctx.header(AGENT_ID_HEADER).orEmpty()
            val result = mapper.readValue<RemoteTaskResult>(ctx.body())
            if (result.taskId != ctx.pathParam("taskId")) {
                ctx.status(400).json(mapOf("error" to "taskId mismatch")); return@post
            }
            try {
                val firstReport = taskStore.complete(agentId, result)
                if (firstReport) {
                    val task = taskStore.task(result.taskId)
                    if (task != null) {
                        deviceContexts?.observe(
                            task,
                            result,
                            registry.find("${task.agentId}:${task.sessionId}"),
                        )
                    }
                    task?.operationId?.let { operationId ->
                        operations?.appendEvent(
                            operationId = operationId,
                            type = if (result.status == com.opentermx.agent.RemoteTaskStatus.SUCCEEDED)
                                OperationEventType.TOOL_SUCCEEDED else OperationEventType.TOOL_REJECTED,
                            source = agentId,
                            correlationId = result.taskId,
                            toolName = "propose_remote_commands",
                            status = result.status.name,
                            payload = mapOf(
                                "taskId" to result.taskId,
                                "executedCount" to result.executedCommands.size,
                                "message" to result.error,
                            ),
                        )
                    }
                }
                ctx.json(mapOf("accepted" to true))
            } catch (e: IllegalArgumentException) {
                ctx.status(409).json(mapOf("error" to (e.message ?: "invalid result")))
            }
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

    private companion object { const val AGENT_ID_HEADER = "X-OpenTermX-Agent-Id" }
}
