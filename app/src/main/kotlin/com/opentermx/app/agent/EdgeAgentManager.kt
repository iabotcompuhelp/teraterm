package com.opentermx.app.agent

import com.opentermx.agent.AgentConnectionState
import com.opentermx.agent.AgentStatus
import com.opentermx.agent.EdgeAgentConfig
import com.opentermx.agent.RemoteTaskProcessor
import com.opentermx.agent.WindowsEdgeAgent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.slf4j.LoggerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

object EdgeAgentManager {
    private val log = LoggerFactory.getLogger(javaClass)
    private val publicState = MutableStateFlow(AgentStatus())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observation: Job? = null
    private var config: EdgeAgentConfig? = null
    private var processor: RemoteTaskProcessor? = null
    private var agent: WindowsEdgeAgent? = null

    @Synchronized
    fun configure(config: EdgeAgentConfig?, processor: RemoteTaskProcessor?) {
        stop()
        this.config = config
        this.processor = processor
        publicState.value = if (config == null) AgentStatus() else AgentStatus(
            state = AgentConnectionState.STOPPED,
            agentId = config.agentId,
            displayName = config.displayName,
            gateway = config.gatewayUri.toString(),
        )
    }

    @Synchronized
    fun start(): Boolean {
        if (agent != null) return true
        val cfg = config ?: return false
        val taskProcessor = processor ?: return false
        return runCatching {
            WindowsEdgeAgent(cfg, taskProcessor = taskProcessor).also {
                agent = it
                it.start()
                observation = scope.launch { it.status.collect { status -> publicState.value = status } }
            }
            true
        }.onFailure { log.error("No se pudo iniciar el agente: {}", it.message) }.getOrDefault(false)
    }

    @Synchronized
    fun stop() {
        val previous = agent
        previous?.close()
        observation?.cancel()
        observation = null
        if (previous != null) publicState.value = previous.status.value
        agent = null
    }

    @Synchronized
    fun restart(): Boolean {
        stop()
        return start()
    }

    @Synchronized
    fun status(): StateFlow<AgentStatus> = publicState

    @Synchronized
    fun isConfigured(): Boolean = config != null
}
