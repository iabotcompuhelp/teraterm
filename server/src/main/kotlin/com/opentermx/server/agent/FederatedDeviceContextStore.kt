package com.opentermx.server.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.opentermx.agent.RemoteCommandTask
import com.opentermx.agent.RemoteTaskResult
import com.opentermx.agent.RemoteTaskStatus
import com.opentermx.ai.context.VendorDetector
import com.opentermx.fingerprint.ProbeRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

data class FederatedDeviceContext(
    val agentId: String,
    val sessionId: String,
    val managementAddress: String?,
    val vendor: String,
    val model: String? = null,
    val osVersion: String? = null,
    val serialNumbers: List<String> = emptyList(),
    val hostname: String? = null,
    val confidence: String,
    val sourceCommand: String,
    val sourceTaskId: String,
    val updatedAtMillis: Long,
    val allowedDiscoveryCommands: List<String>,
)

/**
 * Contexto mínimo del control plane. Solo aprende de resultados SUCCEEDED atribuidos
 * a comandos que el operador aprobó; nunca infiere identidad desde una cola de buffer.
 */
class FederatedDeviceContextStore(private val root: Path) {
    private val mapper = jacksonObjectMapper()
    private val contexts = ConcurrentHashMap<String, FederatedDeviceContext>()

    init { load() }

    fun find(agentId: String, sessionId: String): FederatedDeviceContext? =
        contexts[key(agentId, sessionId)]

    @Synchronized
    fun observe(
        task: RemoteCommandTask,
        result: RemoteTaskResult,
        session: FederatedSession?,
    ): FederatedDeviceContext? {
        if (result.status != RemoteTaskStatus.SUCCEEDED) return null
        val output = result.output?.takeIf { it.isNotBlank() } ?: return null
        val identity = ProbeRegistry.all().firstNotNullOfOrNull { probe ->
            if (probe.matches(output)) probe.extract(output) else null
        }
        val detected = VendorDetector.detect(output).displayName
        val vendor = identity?.vendor?.name ?: detected
        val command = result.executedCommands.lastOrNull()
            ?: task.commands.lastOrNull()
            ?: return null
        val context = FederatedDeviceContext(
            agentId = task.agentId,
            sessionId = task.sessionId,
            managementAddress = session?.snapshot?.host,
            vendor = vendor,
            model = identity?.model,
            osVersion = identity?.osVersion,
            serialNumbers = identity?.serialNumbers ?: emptyList(),
            hostname = identity?.hostname,
            confidence = identity?.confidence?.name ?: if (detected == "Desconocido") "LOW" else "MEDIUM",
            sourceCommand = command,
            sourceTaskId = task.taskId,
            updatedAtMillis = result.completedAtMillis,
            allowedDiscoveryCommands = discoveryCommands(vendor),
        )
        contexts[key(task.agentId, task.sessionId)] = context
        persist(context)
        return context
    }

    private fun persist(context: FederatedDeviceContext) {
        Files.createDirectories(root)
        val target = root.resolve(fileName(context.agentId, context.sessionId))
        val temp = Files.createTempFile(root, ".device-", ".tmp")
        mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), context)
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun load() {
        if (!Files.isDirectory(root)) return
        Files.list(root).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".device.json") }.forEach { path ->
                runCatching { mapper.readValue<FederatedDeviceContext>(path.toFile()) }
                    .getOrNull()?.let { contexts[key(it.agentId, it.sessionId)] = it }
            }
        }
    }

    companion object {
        fun discoveryCommands(vendor: String): List<String> = when {
            vendor.contains("ARUBA", true) -> listOf(
                "show system", "show version", "show interfaces brief", "show lldp neighbor-info",
            )
            vendor.contains("CISCO", true) -> listOf(
                "show version", "show ip interface brief", "show interfaces status", "show lldp neighbors detail",
            )
            else -> listOf("show version")
        }

        private fun key(agentId: String, sessionId: String) = "$agentId:$sessionId"
        private fun fileName(agentId: String, sessionId: String): String =
            (key(agentId, sessionId).replace(Regex("[^A-Za-z0-9._-]"), "_")) + ".device.json"
    }
}
