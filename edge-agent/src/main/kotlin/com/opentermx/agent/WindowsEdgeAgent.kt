package com.opentermx.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = jacksonObjectMapper()
    private val redactor = CredentialRedactor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "opentermx-edge-heartbeat").apply { isDaemon = true }
    }

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
            val request = HttpRequest.newBuilder(config.gatewayUri)
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer ${config.token}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(snapshot())))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
            check(response.statusCode() == 200) { "gateway respondió HTTP ${response.statusCode()}" }
        }.onFailure { log.warn("No se pudo actualizar el control plane: {}", it.message) }
    }

    override fun close() {
        scheduler.shutdownNow()
    }

    private companion object { const val MAX_LINES = 100 }
}
