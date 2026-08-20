package com.opentermx.agent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class CompletedTaskLedger(private val path: Path) {
    private val mapper = jacksonObjectMapper()
    private val results = ConcurrentHashMap<String, RemoteTaskResult>()

    init {
        if (Files.isRegularFile(path)) Files.readAllLines(path).filter { it.isNotBlank() }.forEach { line ->
            runCatching { mapper.readValue<RemoteTaskResult>(line) }.getOrNull()?.let { results[it.taskId] = it }
        }
    }

    fun result(taskId: String): RemoteTaskResult? = results[taskId]

    fun recent(limit: Int = 50): List<RemoteTaskResult> = results.values
        .sortedByDescending { it.completedAtMillis }
        .take(limit)

    @Synchronized
    fun record(result: RemoteTaskResult) {
        if (results.putIfAbsent(result.taskId, result) != null) return
        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            mapper.writeValueAsString(result) + System.lineSeparator(),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }
}
