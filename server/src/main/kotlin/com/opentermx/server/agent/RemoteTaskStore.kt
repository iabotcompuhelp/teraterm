package com.opentermx.server.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.opentermx.agent.AGENT_PROTOCOL_VERSION
import com.opentermx.agent.RemoteCommandTask
import com.opentermx.agent.RemoteTaskResult
import com.opentermx.agent.RemoteTaskStatus
import com.opentermx.agent.RemoteTaskSigner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class RemoteTaskStore(
    private val root: Path,
    signingSecret: ByteArray = "local-test-signing-secret".toByteArray(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val leaseMillis: Long = 15_000,
) {
    private val mapper = jacksonObjectMapper()
    private val signer = RemoteTaskSigner(signingSecret)
    private val tasks = ConcurrentHashMap<String, RemoteCommandTask>()
    private val results = ConcurrentHashMap<String, RemoteTaskResult>()

    init { load() }

    @Synchronized
    fun enqueue(task: RemoteCommandTask): RemoteCommandTask {
        require(task.protocolVersion == AGENT_PROTOCOL_VERSION) { "Versión de protocolo inválida" }
        require(task.taskId.matches(Regex("[A-Za-z0-9._-]{1,96}"))) { "taskId inválido" }
        require(task.commands.isNotEmpty() && task.commands.size <= 50) { "La tarea debe contener entre 1 y 50 comandos" }
        require(task.commands.all { it.isNotBlank() && it.length <= 2_000 }) { "Comando vacío o demasiado largo" }
        require(task.expiresAtMillis > task.createdAtMillis) { "La expiración debe ser posterior a la creación" }
        val signed = signer.sign(task.copy(status = RemoteTaskStatus.PENDING, leaseExpiresAtMillis = null, deliveryAttempt = 0))
        val previous = tasks.putIfAbsent(task.taskId, signed)
        if (previous != null) {
            require(samePayload(previous, task)) { "taskId ya existe con otro contenido" }
            return previous
        }
        persistTask(signed)
        return signed
    }

    @Synchronized
    fun claimNext(agentId: String): RemoteCommandTask? {
        val now = clock()
        expire(now)
        val task = tasks.values
            .filter {
                it.agentId == agentId && (it.status == RemoteTaskStatus.PENDING ||
                    (it.status == RemoteTaskStatus.DELIVERED && (it.leaseExpiresAtMillis ?: 0) <= now))
            }
            .minByOrNull { it.createdAtMillis } ?: return null
        val delivered = task.copy(
            status = RemoteTaskStatus.DELIVERED,
            leaseExpiresAtMillis = now + leaseMillis,
            deliveryAttempt = task.deliveryAttempt + 1,
        )
        tasks[task.taskId] = delivered
        persistTask(delivered)
        return delivered
    }

    @Synchronized
    fun complete(agentId: String, result: RemoteTaskResult): Boolean {
        val task = requireNotNull(tasks[result.taskId]) { "Tarea no encontrada" }
        require(task.agentId == agentId) { "La tarea pertenece a otro agente" }
        require(task.status != RemoteTaskStatus.CANCELLED) { "La tarea fue cancelada" }
        require(result.status in FINAL_STATUSES) { "Estado final inválido" }
        results[result.taskId]?.let { previous ->
            require(previous == result) { "La tarea ya tiene un resultado diferente" }
            return false
        }
        results[result.taskId] = result
        tasks[result.taskId] = task.copy(status = result.status)
        persistTask(tasks.getValue(result.taskId))
        persistResult(result)
        return true
    }

    fun task(id: String): RemoteCommandTask? = tasks[id]
    fun result(id: String): RemoteTaskResult? = results[id]

    @Synchronized
    fun cancel(taskId: String): RemoteCommandTask {
        val task = requireNotNull(tasks[taskId]) { "Tarea no encontrada" }
        require(task.status in setOf(RemoteTaskStatus.PENDING, RemoteTaskStatus.DELIVERED)) {
            "La tarea ya no puede cancelarse"
        }
        val cancelled = task.copy(status = RemoteTaskStatus.CANCELLED, leaseExpiresAtMillis = null)
        tasks[taskId] = cancelled
        persistTask(cancelled)
        return cancelled
    }

    private fun expire(now: Long) {
        tasks.values.filter { it.status == RemoteTaskStatus.PENDING && it.expiresAtMillis <= now }.forEach {
            val expired = it.copy(status = RemoteTaskStatus.EXPIRED)
            tasks[it.taskId] = expired
            persistTask(expired)
        }
    }

    private fun load() {
        if (!Files.isDirectory(root)) return
        Files.list(root).use { paths -> paths.filter { it.fileName.toString().endsWith(".task.json") }.forEach { path ->
            runCatching { mapper.readValue<RemoteCommandTask>(path.toFile()) }.getOrNull()?.let { tasks[it.taskId] = it }
        } }
        Files.list(root).use { paths -> paths.filter { it.fileName.toString().endsWith(".result.json") }.forEach { path ->
            runCatching { mapper.readValue<RemoteTaskResult>(path.toFile()) }.getOrNull()?.let { results[it.taskId] = it }
        } }
    }

    private fun persistTask(task: RemoteCommandTask) = persist("${task.taskId}.task.json", task)
    private fun persistResult(result: RemoteTaskResult) = persist("${result.taskId}.result.json", result)
    private fun persist(name: String, value: Any) {
        Files.createDirectories(root)
        val target = root.resolve(name)
        val temp = Files.createTempFile(root, ".task-", ".tmp")
        mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), value)
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        val FINAL_STATUSES = setOf(RemoteTaskStatus.REJECTED, RemoteTaskStatus.SUCCEEDED, RemoteTaskStatus.FAILED)
    }

    private fun samePayload(a: RemoteCommandTask, b: RemoteCommandTask): Boolean =
        a.taskId == b.taskId && a.operationId == b.operationId && a.agentId == b.agentId &&
            a.sessionId == b.sessionId && a.commands == b.commands && a.rationale == b.rationale &&
            a.createdAtMillis == b.createdAtMillis && a.expiresAtMillis == b.expiresAtMillis
}
