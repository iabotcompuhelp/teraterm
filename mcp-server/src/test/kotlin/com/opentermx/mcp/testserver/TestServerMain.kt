package com.opentermx.mcp.testserver

import com.opentermx.ai.context.Vendor
import com.opentermx.ai.safety.ClassifiedCommand
import com.opentermx.common.ai.CommandSink
import com.opentermx.common.ai.SessionMetadata
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.ai.TerminalBufferProvider
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.McpServer
import com.opentermx.mcp.handlers.CloseSessionHandler
import com.opentermx.mcp.handlers.InspectSessionHandler
import com.opentermx.mcp.handlers.ListMacrosHandler
import com.opentermx.mcp.handlers.ListSessionsHandler
import com.opentermx.mcp.handlers.OpenSessionHandler
import com.opentermx.mcp.handlers.ProposeCommandsHandler
import com.opentermx.mcp.handlers.ReadAuditLogHandler
import com.opentermx.mcp.handlers.RunMacroHandler
import com.opentermx.mcp.handlers.SearchKnowledgeBaseHandler
import com.opentermx.mcp.handlers.TailSessionHandler
import com.opentermx.mcp.security.SessionOpener
import com.opentermx.mcp.security.TailManager
import com.opentermx.mcp.security.ApprovalDecision
import com.opentermx.mcp.security.ApprovalGate

/**
 * Entry point usado por los tests de integración Python para arrancar un `McpServer` real
 * con dos sesiones mock y un [ApprovalGate] determinístico controlado por env vars:
 *
 *  - `MCP_TEST_PORT` (default 8765): puerto donde escucha el server.
 *  - `MCP_TEST_BIND` (default `127.0.0.1`): dirección de bind.
 *  - `MCP_TEST_TOKEN`: si está seteado, el server exige `Authorization: Bearer ...`.
 *  - `OPENTERMX_TEST_AUTO_APPROVE`: si vale `1`, el approval gate aprueba todo. Si vale
 *    `0` (default) rechaza. Si vale `subset:safe`, aprueba solo SAFE.
 *
 * Imprime `READY <host>:<port>` en stdout cuando el server está RUNNING — pytest se queda
 * esperando esa línea antes de empezar a tirar requests.
 */
object TestServerMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val port = System.getenv("MCP_TEST_PORT")?.toIntOrNull() ?: 8765
        val bind = System.getenv("MCP_TEST_BIND") ?: "127.0.0.1"
        val token = System.getenv("MCP_TEST_TOKEN")?.takeIf { it.isNotBlank() }
        val approvePolicy = System.getenv("OPENTERMX_TEST_AUTO_APPROVE").orEmpty()

        seedSessions()

        val gate = TestApprovalGate(approvePolicy)
        // Phase 3 Fase 1: registry de operations in-memory (sin tocar disco real desde tests).
        val operationRegistry = com.opentermx.mcp.operation.OperationRegistry(
            com.opentermx.mcp.operation.InMemoryOperationStore(),
        )
        // Phase 3 Fase 3: secret fijo para tests (32 bytes determinísticos). Permite que el
        // pytest emita un compliance_evaluate, capture el approvalToken, y lo reuse en un
        // propose_commands subsecuente.
        val testSecret = ByteArray(32) { (it + 1).toByte() }
        val secretProvider: () -> ByteArray = { testSecret }
        // Phase 3 Fase 4: snapshot store in-memory.
        val snapshotStore = com.opentermx.mcp.snapshots.InMemorySnapshotStore()
        // Phase 3 Fase 5: policy registry in-memory.
        val policyRegistry = com.opentermx.policy.PolicyRegistry()
        // Phase 3 Fase 2: inventory in-memory con 2 devices mock alineados con las
        // sessions del seedSessions (router-cisco.lab + mk.lab).
        val inventory = StaticInventoryProvider(
            listOf(
                com.opentermx.mcp.inventory.InventoryDevice(
                    alias = "core-router-1", protocol = "SSH", host = "router-cisco.lab", port = 22,
                    username = "admin", deviceType = "cisco_ios",
                    tags = listOf("core", "lab"), groups = listOf("cores"),
                    savedConnectionId = "saved-cisco", displayLabel = "Router Cisco lab",
                ),
                com.opentermx.mcp.inventory.InventoryDevice(
                    alias = "edge-mk-1", protocol = "Telnet", host = "mk.lab", port = 23,
                    username = "admin", deviceType = "mikrotik_routeros",
                    tags = listOf("edge"), groups = listOf("edges"),
                    savedConnectionId = "saved-mk", displayLabel = "Mikrotik Edge",
                ),
            ),
        )
        val handlers = listOf(
            ListSessionsHandler(),
            InspectSessionHandler(),
            SearchKnowledgeBaseHandler { null }, // KB null en tests — handler debe devolver []
            ProposeCommandsHandler(
                gate, injectDelayMillis = 0L,
                operationRegistry = operationRegistry,
                approvalSecretProvider = secretProvider,
                snapshotStore = snapshotStore,
            ),
            // Fase 1 telemetría: whitelist embebida (sin override del usuario) para que
            // el resultado no dependa de ~/.opentermx de la máquina que corre pytest.
            com.opentermx.mcp.handlers.RunReadonlyCommandHandler(
                gate,
                allowWithoutApproval = { true },
                validatorProvider = { com.opentermx.mcp.security.ReadOnlyCommandValidator.embedded() },
            ),
            ListMacrosHandler(),
            RunMacroHandler(gate),
            OpenSessionHandler(gate, SessionOpener.NoOp, inventory),
            CloseSessionHandler(gate),
            ReadAuditLogHandler(),
            TailSessionHandler(TailManager()),
            com.opentermx.mcp.handlers.StartOperationHandler(operationRegistry),
            com.opentermx.mcp.handlers.EndOperationHandler(operationRegistry),
            com.opentermx.mcp.handlers.CurrentOperationHandler(operationRegistry),
            com.opentermx.mcp.handlers.InventoryListHandler(inventory),
            com.opentermx.mcp.handlers.InventoryDescribeHandler(inventory),
            com.opentermx.mcp.handlers.ComplianceEvaluateHandler(operationRegistry, secretProvider),
            com.opentermx.mcp.handlers.SnapshotCreateHandler(snapshotStore, operationRegistry),
            com.opentermx.mcp.handlers.SnapshotDiffHandler(snapshotStore),
            com.opentermx.mcp.handlers.SnapshotCompareToCriteriaHandler(snapshotStore, operationRegistry),
            com.opentermx.mcp.handlers.RollbackProposeHandler(snapshotStore),
            com.opentermx.mcp.handlers.PolicyLoadHandler(policyRegistry),
            com.opentermx.mcp.handlers.PolicyListHandler(policyRegistry),
            com.opentermx.mcp.handlers.PolicyEvaluateHandler(policyRegistry, snapshotStore, inventory),
            com.opentermx.mcp.handlers.PolicyAuditHandler(policyRegistry, snapshotStore, inventory),
        )
        val server = McpServer(
            handlers,
            serverName = "opentermx-mcp-test",
            operationRegistry = operationRegistry,
            // Tests integration corren ráfagas concentradas — el rate limit del prod
            // (60 req/min, burst 20) los agota cuando crece el set de tools. Off acá
            // es seguro: cada server vive un solo run de pytest.
            rateLimitEnabled = false,
        )
        server.start(port, bind, token)

        // Señal a pytest. flush por si el padre buffea.
        println("READY $bind:$port")
        System.out.flush()

        Runtime.getRuntime().addShutdownHook(Thread { server.stop() })

        // Bloqueo trivial. SIGTERM o EOF en stdin baja el proceso.
        val readerThread = Thread {
            runCatching {
                while (true) {
                    val line = readLine() ?: break
                    if (line.trim() == "SHUTDOWN") break
                }
            }
            server.stop()
            kotlin.system.exitProcess(0)
        }
        readerThread.isDaemon = false
        readerThread.start()
        readerThread.join()
    }

    private fun seedSessions() {
        registerMock(
            id = "session-cisco",
            protocol = "SSH",
            host = "router-cisco.lab",
            buffer = listOf(
                "Cisco IOS Software, Version 15.2(4)E10",
                "router>",
            ),
        )
        registerMock(
            id = "session-mikrotik",
            protocol = "Telnet",
            host = "mk.lab",
            port = 23,
            username = "admin",
            buffer = listOf(
                "MikroTik RouterOS 7.10",
                "[admin@mk] >",
            ),
        )
    }

    private fun registerMock(
        id: String,
        protocol: String,
        host: String,
        port: Int = 22,
        username: String = "admin",
        buffer: List<String>,
    ) {
        val sessionId = SessionId(id)
        val provider = TerminalBufferProvider { n -> buffer.takeLast(n) }
        val sink = CommandSink { line -> println("[test-sink:$id] $line"); true }
        SessionRegistry.register(
            sessionId,
            SessionMetadata(name = id, protocol = protocol, host = host, port = port, username = username),
            provider,
            sink,
        )
    }
}

private class StaticInventoryProvider(
    private val devices: List<com.opentermx.mcp.inventory.InventoryDevice>,
) : com.opentermx.mcp.inventory.InventoryProvider {
    override fun list(
        tagsAny: List<String>?,
        groupsAny: List<String>?,
        deviceType: String?,
    ): List<com.opentermx.mcp.inventory.InventoryDevice> = devices
        .filter { tagsAny.isNullOrEmpty() || it.tags.any { t -> t in tagsAny } }
        .filter { groupsAny.isNullOrEmpty() || it.groups.any { g -> g in groupsAny } }
        .filter { deviceType.isNullOrBlank() || it.deviceType.equals(deviceType, ignoreCase = true) }

    override fun byAlias(alias: String): com.opentermx.mcp.inventory.InventoryDevice? =
        devices.firstOrNull { it.alias == alias }
}

private class TestApprovalGate(private val policy: String) : ApprovalGate {
    override suspend fun reviewCommands(
        prompt: String,
        vendor: Vendor,
        classifications: List<ClassifiedCommand>,
    ): ApprovalDecision = when {
        policy == "1" || policy.equals("true", ignoreCase = true) ->
            ApprovalDecision.Approve(classifications.map { it.raw }, classifications.map { it.risk })
        policy.startsWith("subset:safe") -> {
            val kept = classifications.filter { it.risk.name == "SAFE" }
            ApprovalDecision.Approve(kept.map { it.raw }, kept.map { it.risk })
        }
        else -> ApprovalDecision.Reject
    }
}