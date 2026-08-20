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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

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
    private val completedTasks = CompletedTaskLedger(config.stateDir.resolve("completed-tasks.log"))
    private val signer = RemoteTaskSigner(config.token.toByteArray(Charsets.UTF_8))
    private val started = AtomicBoolean(false)
    private val statusState = MutableStateFlow(
        AgentStatus(
            state = AgentConnectionState.DISABLED,
            agentId = config.agentId,
            displayName = config.displayName,
            gateway = config.gatewayUri.toString(),
            history = completedTasks.recent().map {
                AgentTaskHistoryEntry(it.taskId, it.status, it.completedAtMillis, it.executedCommands.size, it.error)
            },
        ),
    )
    val status: StateFlow<AgentStatus> = statusState.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        updateStatus { copy(state = AgentConnectionState.STARTING, lastError = null) }
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
            val heartbeatTime = System.currentTimeMillis()
            updateStatus {
                copy(
                    state = AgentConnectionState.CONNECTED,
                    lastHeartbeatMillis = heartbeatTime,
                    activeSessions = SessionRegistry.activeSessions().size,
                    consecutiveFailures = 0,
                    lastError = null,
                )
            }
            pollTask()
        }.onFailure { error ->
            log.warn("No se pudo actualizar el control plane: {}", error.message)
            updateStatus {
                copy(
                    state = AgentConnectionState.DEGRADED,
                    consecutiveFailures = consecutiveFailures + 1,
                    lastError = error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    private fun pollTask() {
        val uri = config.gatewayUri.resolve("/agent/v1/tasks/next?agentId=${config.agentId}")
        val request = authorizedRequest(uri).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 204) return
        check(response.statusCode() == 200) { "poll de tareas respondió HTTP ${response.statusCode()}" }
        val task = mapper.readValue<RemoteCommandTask>(response.body())
        if (task.agentId != config.agentId || task.expiresAtMillis <= System.currentTimeMillis()) return
        check(signer.verify(task)) { "firma HMAC inválida para tarea ${task.taskId}" }
        updateStatus { copy(currentTaskId = task.taskId) }
        completedTasks.result(task.taskId)?.let { previous ->
            submitResult(previous)
            recordHistory(previous)
            return
        }
        val result = runCatching { taskProcessor.process(task) }.getOrElse { error ->
            RemoteTaskResult(task.taskId, RemoteTaskStatus.FAILED, System.currentTimeMillis(), error = error.message)
        }
        completedTasks.record(result)
        submitResult(result)
        recordHistory(result)
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
        if (!started.compareAndSet(true, false)) return
        scheduler.shutdownNow()
        updateStatus { copy(state = AgentConnectionState.STOPPED, currentTaskId = null) }
    }

    private fun recordHistory(result: RemoteTaskResult) {
        updateStatus {
            val entry = AgentTaskHistoryEntry(
                result.taskId, result.status, result.completedAtMillis,
                result.executedCommands.size, result.error,
            )
            copy(currentTaskId = null, history = (listOf(entry) + history.filterNot { it.taskId == entry.taskId }).take(50))
        }
    }

    private inline fun updateStatus(transform: AgentStatus.() -> AgentStatus) {
        statusState.value = statusState.value.transform()
    }

    private companion object { const val MAX_LINES = 100 }
}
