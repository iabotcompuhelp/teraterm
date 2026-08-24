package com.opentermx.server.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.opentermx.agent.AgentHeartbeat
import com.opentermx.agent.AgentHeartbeatAck
import com.opentermx.agent.RemoteTaskResult
import io.javalin.Javalin
import com.opentermx.mcp.operation.OperationEventType
import com.opentermx.mcp.operation.OperationRegistry

class AgentGateway(
    private val bindAddress: String,
    private val port: Int,
    private val credentials: AgentCredentials,
    private val registry: AgentRegistry,
    private val taskStore: RemoteTaskStore,
    private val operations: OperationRegistry? = null,
    private val deviceContexts: FederatedDeviceContextStore? = null,
    private val onHeartbeat: (AgentHeartbeat, String?) -> Unit = { _, _ -> },
    private val onDenied: (String?, String?, String) -> Unit = { _, _, _ -> },
) : AutoCloseable {
    constructor(
        bindAddress: String,
        port: Int,
        token: String,
        registry: AgentRegistry,
        taskStore: RemoteTaskStore,
        operations: OperationRegistry? = null,
        deviceContexts: FederatedDeviceContextStore? = null,
    ) : this(
        bindAddress, port, SharedAgentCredentials(token), registry, taskStore, operations, deviceContexts,
    )

    private val mapper = jacksonObjectMapper()
    private var app: Javalin? = null

    fun start() {
        check(app == null) { "Agent gateway ya está iniciado" }
        app = Javalin.create { config ->
            config.showJavalinBanner = false
            config.http.maxRequestSize = 1_048_576
        }.post("/agent/v1/heartbeat") { ctx ->
            val claimedAgent = ctx.header(AGENT_ID_HEADER).orEmpty()
            if (!authorized(claimedAgent, ctx.header("Authorization"))) {
                onDenied(claimedAgent.ifBlank { null }, ctx.ip(), "AUTH_FAILED")
                ctx.status(401).json(mapOf("error" to "unauthorized"))
                return@post
            }
            try {
                val heartbeat = mapper.readValue<AgentHeartbeat>(ctx.body())
                if (claimedAgent != heartbeat.agentId) {
                    onDenied(claimedAgent, ctx.ip(), "IDENTITY_MISMATCH")
                    ctx.status(403).json(mapOf("error" to "agent identity mismatch"))
                    return@post
                }
                registry.update(heartbeat)
                onHeartbeat(heartbeat, ctx.ip())
                ctx.json(AgentHeartbeatAck(true, System.currentTimeMillis(), 5))
            } catch (e: IllegalArgumentException) {
                ctx.status(400).json(mapOf("error" to (e.message ?: "invalid heartbeat")))
            }
        }.get("/agent/v1/health") { ctx ->
            ctx.json(mapOf("status" to "ok", "activeSessions" to registry.sessions().size))
        }.get("/agent/v1/tasks/next") { ctx ->
            val agentId = ctx.queryParam("agentId").orEmpty()
            if (!authorized(ctx.header(AGENT_ID_HEADER).orEmpty(), ctx.header("Authorization"))) {
                onDenied(ctx.header(AGENT_ID_HEADER), ctx.ip(), "AUTH_FAILED")
                ctx.status(401); return@get
            }
            if (agentId.isBlank() || ctx.header(AGENT_ID_HEADER) != agentId) {
                ctx.status(403); return@get
            }
            val task = taskStore.claimNext(agentId)
            if (task == null) ctx.status(204) else ctx.json(task)
        }.post("/agent/v1/tasks/{taskId}/result") { ctx ->
            val agentId = ctx.header(AGENT_ID_HEADER).orEmpty()
            if (!authorized(agentId, ctx.header("Authorization"))) {
                onDenied(agentId.ifBlank { null }, ctx.ip(), "AUTH_FAILED")
                ctx.status(401); return@post
            }
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

    private fun authorized(agentId: String, header: String?): Boolean {
        if (agentId.isBlank()) return false
        val supplied = header?.removePrefix("Bearer ") ?: return false
        return credentials.authenticate(agentId, supplied)
    }

    override fun close() {
        app?.stop()
        app = null
    }

    private companion object { const val AGENT_ID_HEADER = "X-OpenTermX-Agent-Id" }
}
