package com.opentermx.server

import com.opentermx.ai.safety.CredentialRedactor
import com.opentermx.mcp.McpServer
import com.opentermx.mcp.handlers.CurrentOperationHandler
import com.opentermx.mcp.handlers.EndOperationHandler
import com.opentermx.mcp.handlers.ExportOperationHandoffHandler
import com.opentermx.mcp.handlers.InspectSessionHandler
import com.opentermx.mcp.handlers.ListSessionsHandler
import com.opentermx.mcp.handlers.ResumeOperationHandler
import com.opentermx.mcp.handlers.StartOperationHandler
import com.opentermx.mcp.handlers.ToolHandler
import com.opentermx.mcp.operation.FsOperationStore
import com.opentermx.mcp.operation.OperationRegistry
import com.opentermx.mcp.snapshots.FsSnapshotStore
import java.nio.file.Files

class HeadlessServerRuntime(private val config: ServerConfig) : AutoCloseable {
    private val redactor = CredentialRedactor()
    private val operationRoot = config.dataDir.resolve("operations")
    private val snapshotRoot = config.dataDir.resolve("snapshots")
    private val contextRoot = config.dataDir.resolve("contexts")
    private val snapshotStore = FsSnapshotStore(operationRoot, snapshotRoot)
    private val operationRegistry = OperationRegistry(FsOperationStore(operationRoot))

    internal val handlers: List<ToolHandler> = listOf(
        ListSessionsHandler(),
        InspectSessionHandler(redactor),
        StartOperationHandler(operationRegistry, contextRoot),
        CurrentOperationHandler(operationRegistry),
        ResumeOperationHandler(operationRegistry),
        ExportOperationHandoffHandler(operationRegistry, snapshotStore),
        EndOperationHandler(operationRegistry),
    )

    private val server = McpServer(
        handlers = handlers,
        serverName = "opentermx-control-plane",
        readOnly = true,
        operationRegistry = operationRegistry,
        redactor = redactor,
    )

    fun start() {
        Files.createDirectories(operationRoot)
        Files.createDirectories(snapshotRoot)
        Files.createDirectories(contextRoot)
        server.start(config.port, config.bindAddress, config.token)
    }

    fun binding(): McpServer.Binding? = server.binding()

    override fun close() = server.stop()
}
