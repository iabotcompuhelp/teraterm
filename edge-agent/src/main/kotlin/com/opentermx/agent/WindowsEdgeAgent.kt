package com.opentermx.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.opentermx.ai.context.VendorDetector
import com.opentermx.ai.safety.CredentialRedactor
import com.opentermx.common.ai.SessionRegistry
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

class WindowsEdgeAgent(
    private val config: EdgeAgentConfig,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    private val taskProcessor: RemoteTaskProcessor = RemoteTaskProcessor.RejectUnavailable,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()
    private val redactor = CredentialRedactor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "opentermx-edge-heartbeat").apply { isDaemon = true }
    }
    private val completedTaskIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun start() {
        scheduler.scheduleWithFixedDelay(::sendHeartbeatSafely, 0, config.heartbeatSeconds, TimeUnit.SECONDS)
        log.info("Agente de borde habilitado como {} hacia {}", config.agentId, config.gatewayUri)
    }

    internal fun snapshot(): AgentHeartbeat = AgentHeartbeat(
        agentId = config.agentId,
        displayName = config.displayName,
        platform = System.getProperty("os.name"),
        sentAtMillis = System.currentTimeMillis(),
        sessions = SessionRegistry.activeSessions().map { descriptor ->
            val raw = SessionRegistry.lastLinesOf(descriptor.id, MAX_LINES)
            val vendor = VendorDetector.detect(raw.joinToString("\n"))
            AgentSessionSnapshot(
                sessionId = descriptor.id.value,
                name = descriptor.metadata.name,
                protocol = descriptor.metadata.protocol,
                host = descriptor.metadata.host,
                port = descriptor.metadata.port,
                username = descriptor.metadata.username,
                lines = redactor.redactLines(raw, vendor),
            )
        },
    )

    private fun sendHeartbeatSafely() {
        runCatching {
            val request = authorizedRequest(config.gatewayUri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(snapshot())))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
            check(response.statusCode() == 200) { "gateway respondió HTTP ${response.statusCode()}" }
            pollTask()
        }.onFailure { log.warn("No se pudo actualizar el control plane: {}", it.message) }
    }

    private fun pollTask() {
        val uri = config.gatewayUri.resolve("/agent/v1/tasks/next?agentId=${config.agentId}")
        val request = authorizedRequest(uri).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 204) return
        check(response.statusCode() == 200) { "poll de tareas respondió HTTP ${response.statusCode()}" }
        val task = mapper.readValue<RemoteCommandTask>(response.body())
        if (task.agentId != config.agentId || task.expiresAtMillis <= System.currentTimeMillis()) return
        if (!completedTaskIds.add(task.taskId)) return
        val result = runCatching { taskProcessor.process(task) }.getOrElse { error ->
            RemoteTaskResult(task.taskId, RemoteTaskStatus.FAILED, System.currentTimeMillis(), error = error.message)
        }
        submitResult(result)
    }

    private fun submitResult(result: RemoteTaskResult) {
        val uri = config.gatewayUri.resolve("/agent/v1/tasks/${result.taskId}/result")
        val request = authorizedRequest(uri)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(result)))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        check(response.statusCode() == 200) { "reporte de tarea respondió HTTP ${response.statusCode()}" }
    }

    private fun authorizedRequest(uri: java.net.URI): HttpRequest.Builder = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(10))
        .header("Authorization", "Bearer ${config.token}")
        .header("X-OpenTermX-Agent-Id", config.agentId)

    override fun close() {
        scheduler.shutdownNow()
    }

    private companion object { const val MAX_LINES = 100 }
}
