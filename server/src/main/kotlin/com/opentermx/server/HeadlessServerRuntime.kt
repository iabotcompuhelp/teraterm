package com.opentermx.server

import com.opentermx.ai.safety.CredentialRedactor
import com.opentermx.mcp.McpServer
import com.opentermx.mcp.handlers.CurrentOperationHandler
import com.opentermx.mcp.handlers.EndOperationHandler
import com.opentermx.mcp.handlers.ExportOperationHandoffHandler
import com.opentermx.mcp.handlers.ResumeOperationHandler
import com.opentermx.mcp.handlers.StartOperationHandler
import com.opentermx.mcp.handlers.ToolHandler
import com.opentermx.mcp.operation.FsOperationStore
import com.opentermx.mcp.operation.OperationRegistry
import com.opentermx.mcp.snapshots.FsSnapshotStore
import java.nio.file.Files
import com.opentermx.server.agent.AgentGateway
import com.opentermx.server.agent.AgentRegistry
import com.opentermx.server.agent.FederatedInspectSessionHandler
import com.opentermx.server.agent.FederatedListSessionsHandler
import com.opentermx.server.agent.FederatedDeviceContextStore
import com.opentermx.server.agent.RemoteTaskStore
import com.opentermx.server.agent.ProposeRemoteCommandsHandler
import com.opentermx.server.agent.GetRemoteTaskHandler
import com.opentermx.server.agent.CancelRemoteTaskHandler

class HeadlessServerRuntime(private val config: ServerConfig) : AutoCloseable {
    private val agentRegistry = AgentRegistry()
    internal val remoteTaskStore = RemoteTaskStore(
        config.dataDir.resolve("tasks"),
        (config.agentToken ?: "disabled-agent-gateway-secret").toByteArray(Charsets.UTF_8),
    )
    private val redactor = CredentialRedactor()
    private val operationRoot = config.dataDir.resolve("operations")
    private val snapshotRoot = config.dataDir.resolve("snapshots")
    private val contextRoot = config.dataDir.resolve("contexts")
    private val deviceContextStore = FederatedDeviceContextStore(config.dataDir.resolve("devices"))
    private val snapshotStore = FsSnapshotStore(operationRoot, snapshotRoot)
    private val operationRegistry = OperationRegistry(FsOperationStore(operationRoot))

    internal val handlers: List<ToolHandler> = listOf(
        FederatedListSessionsHandler(agentRegistry, deviceContextStore),
        FederatedInspectSessionHandler(agentRegistry, deviceContextStore),
        StartOperationHandler(operationRegistry, contextRoot),
        CurrentOperationHandler(operationRegistry),
        ResumeOperationHandler(operationRegistry),
        ExportOperationHandoffHandler(operationRegistry, snapshotStore),
        EndOperationHandler(operationRegistry),
        ProposeRemoteCommandsHandler(operationRegistry, agentRegistry, remoteTaskStore),
        GetRemoteTaskHandler(operationRegistry, remoteTaskStore),
        CancelRemoteTaskHandler(operationRegistry, remoteTaskStore),
    )

    private val server = McpServer(
        handlers = handlers,
        serverName = "opentermx-control-plane",
        readOnly = config.readOnly,
        operationRegistry = operationRegistry,
        redactor = redactor,
    )
    private val agentGateway = config.agentToken?.let {
        AgentGateway(
            config.bindAddress, config.agentPort, it, agentRegistry, remoteTaskStore,
            operationRegistry, deviceContextStore,
        )
    }

    fun start() {
        Files.createDirectories(operationRoot)
        Files.createDirectories(snapshotRoot)
        Files.createDirectories(contextRoot)
        server.start(config.port, config.bindAddress, config.token)
        try {
            agentGateway?.start()
        } catch (e: Exception) {
            server.stop()
            throw e
        }
    }

    fun binding(): McpServer.Binding? = server.binding()

    override fun close() {
        agentGateway?.close()
        server.stop()
    }
}
