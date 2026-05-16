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
        val handlers = listOf(
            ListSessionsHandler(),
            InspectSessionHandler(),
            SearchKnowledgeBaseHandler { null }, // KB null en tests — handler debe devolver []
            ProposeCommandsHandler(gate, injectDelayMillis = 0L),
            ListMacrosHandler(),
            RunMacroHandler(gate),
            OpenSessionHandler(gate, SessionOpener.NoOp),
            CloseSessionHandler(gate),
            ReadAuditLogHandler(),
            TailSessionHandler(TailManager()),
        )
        val server = McpServer(handlers, serverName = "opentermx-mcp-test")
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