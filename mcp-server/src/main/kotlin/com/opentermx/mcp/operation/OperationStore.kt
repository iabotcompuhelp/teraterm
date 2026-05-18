package com.opentermx.mcp.operation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Persistencia de operations activas. Se inyecta en [OperationRegistry] para que los tests
 * puedan usar [InMemoryOperationStore] y en producción [FsOperationStore] vuelque a
 * `~/.opentermx/operations/<op-id>/context.json` + flag `closed.json` cuando cerramos.
 */
interface OperationStore {
    fun save(record: OperationRecord)
    fun markClosed(operationId: String, closedAtMillis: Long, summary: Map<String, Any?>)
    fun loadAllOpen(): List<OperationRecord>
}

/**
 * Lo que persistimos por operation: el context original + timestamps + sessionKeys del MCP
 * que iniciaron y consumieron la op (para auditoría cruzada con audit log).
 */
data class OperationRecord(
    val operationId: String,
    val context: OperationContext,
    val startedAtMillis: Long,
    val initiatedBySessionKey: String,
)

class FsOperationStore(
    private val rootDir: Path = defaultRoot(),
) : OperationStore {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    override fun save(record: OperationRecord) {
        runCatching {
            val dir = rootDir.resolve(record.operationId)
            Files.createDirectories(dir)
            mapper.writerWithDefaultPrettyPrinter()
                .writeValue(dir.resolve("context.json").toFile(), record)
        }.onFailure { e ->
            log.warn("No se pudo persistir operation ${record.operationId}: ${e.message}")
        }
    }

    override fun markClosed(operationId: String, closedAtMillis: Long, summary: Map<String, Any?>) {
        runCatching {
            val dir = rootDir.resolve(operationId)
            if (!Files.isDirectory(dir)) return  // nada que cerrar
            val payload = linkedMapOf<String, Any?>(
                "operationId" to operationId,
                "closedAtMillis" to closedAtMillis,
                "summary" to summary,
            )
            mapper.writerWithDefaultPrettyPrinter()
                .writeValue(dir.resolve("closed.json").toFile(), payload)
        }.onFailure { e ->
            log.warn("No se pudo marcar cerrada la operation $operationId: ${e.message}")
        }
    }

    override fun loadAllOpen(): List<OperationRecord> {
        if (!Files.isDirectory(rootDir)) return emptyList()
        return Files.list(rootDir).use { stream ->
            stream
                .filter { Files.isDirectory(it) }
                .filter { !Files.exists(it.resolve("closed.json")) }
                .map { dir ->
                    runCatching {
                        val ctxFile = dir.resolve("context.json")
                        if (!Files.isRegularFile(ctxFile)) return@map null
                        mapper.readValue<OperationRecord>(ctxFile.toFile())
                    }.onFailure { e ->
                        log.warn("No se pudo recuperar operation en $dir: ${e.message}")
                    }.getOrNull()
                }
                .filter { it != null }
                .map { it!! }
                .toList()
        }
    }

    companion object {
        fun defaultRoot(): Path =
            Path.of(System.getProperty("user.home"), ".opentermx", "operations")
    }
}

/** Implementación in-memory para tests; no toca disco. */
class InMemoryOperationStore : OperationStore {
    private val open = mutableMapOf<String, OperationRecord>()
    private val closed = mutableMapOf<String, Map<String, Any?>>()

    @Synchronized override fun save(record: OperationRecord) {
        open[record.operationId] = record
    }

    @Synchronized override fun markClosed(operationId: String, closedAtMillis: Long, summary: Map<String, Any?>) {
        open.remove(operationId)
        closed[operationId] = summary
    }

    @Synchronized override fun loadAllOpen(): List<OperationRecord> = open.values.toList()

    @Synchronized fun closedSummary(operationId: String): Map<String, Any?>? = closed[operationId]
}
