package com.opentermx.server.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.opentermx.agent.AGENT_PROTOCOL_VERSION
import com.opentermx.agent.RemoteCommandTask
import com.opentermx.agent.RemoteTaskResult
import com.opentermx.agent.RemoteTaskStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class RemoteTaskStore(private val root: Path, private val clock: () -> Long = System::currentTimeMillis) {
    private val mapper = jacksonObjectMapper()
    private val tasks = ConcurrentHashMap<String, RemoteCommandTask>()
    private val results = ConcurrentHashMap<String, RemoteTaskResult>()

    init { load() }

    @Synchronized
    fun enqueue(task: RemoteCommandTask): RemoteCommandTask {
        require(task.protocolVersion == AGENT_PROTOCOL_VERSION) { "Versión de protocolo inválida" }
        require(task.taskId.matches(Regex("[A-Za-z0-9._-]{1,96}"))) { "taskId inváido" }
        require(task.commands.isNotEmpty() && task.commands.size <= 50) { "La tarea debe contener entre 1 y 50 comandos" }
        require(task.commands.all { it.isNotBlank() && it.length <= 2_000 }) { "Comando vacío o demasiado largo" }
        require(task.expiresAtMillis > task.createdAtMillis) { "La expiración debe ser posterior a la creación" }
        val previous = tasks.putIfAbsent(task.taskId, task)
        if (previous != null) {
            require(previous == task) { "taskId ya existe con otro contenido" }
            return previous
        }
        persistTask(task)
        return task
    }

    @Synchronized
    fun claimNext(agentId: String): RemoteCommandTask? {
        val now = clock()
        expire(now)
        val task = tasks.values
            .filter { it.agentId == agentId && it.status == RemoteTaskStatus.PENDING }
            .minByOrNull { it.createdAtMillis } ?: return null
        val delivered = task.copy(status = RemoteTaskStatus.DELIVERED)
        tasks[task.taskId] = delivered
        persistTask(delivered)
        return delivered
    }

    @Synchronized
    fun complete(agentId: String, result: RemoteTaskResult) {
        val task = requireNotNull(tasks[result.taskId]) { "Tarea no encontrada" }
        require(task.agentId == agentId) { "La tarea pertenece a otro agente" }
        require(result.status in FINAL_STATUSES) { "Estado final inválido" }
        results[result.taskId] = result
        tasks[result.taskId] = task.copy(status = result.status)
        persistTask(tasks.getValue(result.taskId))
        persistResult(result)
    }

    fun task(id: String): RemoteCommandTask? = tasks[id]
    fun result(id: String): RemoteTaskResult? = results[id]

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
}
