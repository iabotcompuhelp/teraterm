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
import com.opentermx.server.agent.AgentCredentials
import com.opentermx.server.agent.FileAgentCredentials
import com.opentermx.server.agent.SharedAgentCredentials
import com.opentermx.telemetrydb.DbConfig
import com.opentermx.telemetrydb.TelemetryDb
import com.opentermx.server.graphql.ControlPlaneGraphql

class HeadlessServerRuntime(private val config: ServerConfig) : AutoCloseable {
    private var telemetryDb: TelemetryDb? = null
    private val agentRegistry = AgentRegistry()
    private val agentCredentials: AgentCredentials? = when {
        config.agentCredentialsFile != null -> FileAgentCredentials(config.agentCredentialsFile) { agentId, event ->
            telemetryDb?.agents?.recordCredentialLifecycle(agentId, event)
        }
        config.agentToken != null -> SharedAgentCredentials(config.agentToken)
        else -> null
    }
    internal val remoteTaskStore = RemoteTaskStore(
        config.dataDir.resolve("tasks"),
        { agentId -> agentCredentials?.signingSecret(agentId) },
    )
    private val redactor = CredentialRedactor()
    private val operationRoot = config.dataDir.resolve("operations")
    private val snapshotRoot = config.dataDir.resolve("snapshots")
    private val contextRoot = config.dataDir.resolve("contexts")
    private val deviceContextStore = FederatedDeviceContextStore(config.dataDir.resolve("devices"))
    private val snapshotStore = FsSnapshotStore(operationRoot, snapshotRoot)
    private val operationRegistry = OperationRegistry(FsOperationStore(operationRoot))
    private val graphql = ControlPlaneGraphql(
        database = { telemetryDb },
        agents = {
            val active = agentRegistry.snapshots()
            val activeIds = active.mapTo(hashSetOf()) { it.agentId }
            telemetryDb?.agents?.list()?.map { it + ("online" to (it["agentId"] in activeIds)) }
                ?: active.map {
                    mapOf(
                        "agentId" to it.agentId, "displayName" to it.displayName, "platform" to it.platform,
                        "lastSeenAtMillis" to it.lastSeenAtMillis.toString(), "sessionCount" to it.sessionCount,
                        "online" to true,
                    )
                }
        },
        tasks = remoteTaskStore::list,
    )

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
        additionalRoutes = graphql::install,
    )
    private val agentGateway = agentCredentials?.let {
        AgentGateway(
            config.bindAddress, config.agentPort, it, agentRegistry, remoteTaskStore,
            operationRegistry, deviceContextStore,
            onHeartbeat = { heartbeat, remoteAddress ->
                telemetryDb?.agents?.observe(
                    heartbeat.agentId, heartbeat.displayName, heartbeat.platform,
                    heartbeat.agentVersion, heartbeat.protocolVersion, remoteAddress, heartbeat.sessions.size,
                )
            },
            onDenied = { agentId, remoteAddress, eventType ->
                telemetryDb?.agents?.recordDenied(agentId, remoteAddress, eventType)
            },
        )
    }

    fun start() {
        Files.createDirectories(operationRoot)
        Files.createDirectories(snapshotRoot)
        Files.createDirectories(contextRoot)
        try {
            telemetryDb = config.database?.let { database ->
                TelemetryDb.connect(
                    DbConfig(database.host, database.port, database.database, database.username, database.password),
                ).getOrElse { error ->
                    if (database.required) {
                        throw IllegalStateException("PostgreSQL requerido no está disponible", error)
                    }
                    null
                }
            }
            server.start(config.port, config.bindAddress, config.token)
            agentGateway?.start()
        } catch (e: Exception) {
            server.stop()
            telemetryDb?.close()
            telemetryDb = null
            throw e
        }
    }

    fun binding(): McpServer.Binding? = server.binding()

    override fun close() {
        agentGateway?.close()
        server.stop()
        telemetryDb?.close()
        telemetryDb = null
    }
}
