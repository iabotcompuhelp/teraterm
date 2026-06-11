package com.opentermx.mcp.handlers

import com.opentermx.ai.context.Vendor
import com.opentermx.ai.safety.ClassifiedCommand
import com.opentermx.ai.safety.RiskLevel
import com.opentermx.common.ai.SessionMetadata
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.ai.TerminalBufferProvider
import com.opentermx.common.ai.CommandSink
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.security.ApprovalDecision
import com.opentermx.mcp.security.ApprovalGate

/**
 * Helpers compartidos por los unit tests. Encapsulan el registro/limpieza de sesiones
 * en el singleton [SessionRegistry] y proveen un [ApprovalGate] in-memory configurable.
 */
internal object TestFixtures {

    fun registerSession(
        idValue: String,
        protocol: String,
        host: String? = "1.2.3.4",
        port: Int? = 22,
        username: String? = "admin",
        buffer: List<String> = emptyList(),
        sink: CommandSink? = null,
    ): SessionId {
        val id = SessionId(idValue)
        val meta = SessionMetadata(name = idValue, protocol = protocol, host = host, port = port, username = username)
        val provider = TerminalBufferProvider { n -> buffer.takeLast(n) }
        SessionRegistry.register(id, meta, provider, sink)
        return id
    }

    fun unregisterAll() {
        SessionRegistry.activeSessions().forEach { SessionRegistry.unregister(it.id) }
    }
}

/**
 * Sink que captura comandos en memoria. [shouldFail] permite simular un envío que falla
 * (la conexión cayó, por ejemplo).
 */
internal class CapturingSink(
    private val shouldFail: (String) -> Boolean = { false },
) : CommandSink {
    val sent = mutableListOf<String>()
    override fun sendLine(line: String): Boolean {
        sent += line
        return !shouldFail(line)
    }
}

/**
 * Approval gate determinístico para tests. Por defecto rechaza — los tests configuran
 * `nextDecision` antes de invocar el handler.
 */
internal class FakeApprovalGate(
    var nextDecision: (List<ClassifiedCommand>) -> ApprovalDecision = { ApprovalDecision.Reject },
) : ApprovalGate {

    val invocations = mutableListOf<ReviewCall>()

    override suspend fun reviewCommands(
        prompt: String,
        vendor: Vendor,
        classifications: List<ClassifiedCommand>,
    ): ApprovalDecision {
        invocations += ReviewCall(prompt, vendor, classifications)
        return nextDecision(classifications)
    }

    fun approveAll() {
        nextDecision = { cls -> ApprovalDecision.Approve(cls.map { it.raw }, cls.map { it.risk }) }
    }

    fun rejectAll() {
        nextDecision = { ApprovalDecision.Reject }
    }

    fun approveSubset(filter: (ClassifiedCommand) -> Boolean) {
        nextDecision = { cls ->
            val kept = cls.filter(filter)
            ApprovalDecision.Approve(kept.map { it.raw }, kept.map { it.risk })
        }
    }
}

internal data class ReviewCall(
    val prompt: String,
    val vendor: Vendor,
    val classifications: List<ClassifiedCommand>,
)

/** Sugar para los asserts sobre el riskSummary devuelto. */
internal fun riskSummary(safe: Int, config: Int, dangerous: Int): Map<String, Int> =
    linkedMapOf("safe" to safe, "config" to config, "dangerous" to dangerous)

/**
 * Simulador de equipo para los tests del [com.opentermx.mcp.exec.SessionCommandRunner]:
 * un buffer mutable + un sink que "responde" cada comando appendeando líneas (eco,
 * output y prompt). [responder] es reemplazable por test; [responseDelayMillis] simula
 * un equipo lento (la respuesta llega en otro thread).
 */
internal class FakeDevice(
    initialBuffer: List<String> = listOf("Cisco IOS Software, Version 15.2", PROMPT),
    private val prompt: String = PROMPT,
) {
    val buffer = java.util.concurrent.CopyOnWriteArrayList(initialBuffer)
    val received = java.util.concurrent.CopyOnWriteArrayList<String>()
    val bufferSizeAtReceive = java.util.concurrent.CopyOnWriteArrayList<Int>()

    var responder: (String) -> List<String> = { cmd ->
        listOf("$prompt $cmd", "output de [$cmd] linea 1", "output de [$cmd] linea 2", prompt)
    }
    var responseDelayMillis: Long = 0

    val provider = TerminalBufferProvider { n -> buffer.toList().takeLast(n) }
    val sink = CommandSink { line ->
        received += line
        bufferSizeAtReceive += buffer.size
        val lines = responder(line)
        if (responseDelayMillis > 0) {
            Thread {
                Thread.sleep(responseDelayMillis)
                buffer.addAll(lines)
            }.also { it.isDaemon = true }.start()
        } else {
            buffer.addAll(lines)
        }
        true
    }

    fun register(idValue: String, host: String? = "1.2.3.4"): SessionId {
        val id = SessionId(idValue)
        SessionRegistry.register(
            id,
            SessionMetadata(name = idValue, protocol = "SSH", host = host, port = 22, username = "admin"),
            provider,
            sink,
        )
        return id
    }

    companion object {
        const val PROMPT = "router-1#"
    }
}