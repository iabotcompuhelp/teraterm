package com.opentermx.mcp.snapshots

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * Phase 3 Fase 4 — Snapshots.
 *
 * Captura del estado de un device en un punto dado. La fuente real de la captura es el
 * buffer del [com.opentermx.common.ai.SessionRegistry] — el LLM cliente ejecuta los
 * comandos canónicos antes (vía `propose_commands`) y luego llama `snapshot_create` para
 * congelar el output. Eso evita lidiar con timing/async dentro del handler y mantiene la
 * acción explícita.
 *
 * Storage default: `~/.opentermx/operations/<op-id>/snapshots/<id>.json` (cuando hay op
 * activa) o `~/.opentermx/snapshots/<id>.json` (cuando no hay op).
 */
data class Snapshot(
    val id: String,
    val operationId: String?,
    val sessionId: String,
    val deviceAlias: String?,
    val snapshotType: String,
    val timestampMillis: Long,
    val contentHash: String,
    val content: String,
    val label: String? = null,
) {

    companion object {
        fun newId(): String = "snap-" + UUID.randomUUID().toString().take(12)

        fun hashOf(content: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

interface SnapshotStore {
    fun save(snapshot: Snapshot)
    fun load(id: String): Snapshot?
    fun listForOperation(operationId: String): List<Snapshot>
    fun listForDevice(operationId: String?, deviceAlias: String?, sessionId: String?): List<Snapshot>
}

class FsSnapshotStore(
    private val operationsRoot: Path = defaultOperationsRoot(),
    private val orphanRoot: Path = defaultOrphanRoot(),
) : SnapshotStore {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    override fun save(snapshot: Snapshot) {
        runCatching {
            val dir = dirFor(snapshot.operationId)
            Files.createDirectories(dir)
            mapper.writerWithDefaultPrettyPrinter()
                .writeValue(dir.resolve("${snapshot.id}.json").toFile(), snapshot)
        }.onFailure { e ->
            log.warn("No se pudo persistir snapshot ${snapshot.id}: ${e.message}")
        }
    }

    override fun load(id: String): Snapshot? {
        // Buscamos primero en operations roots, luego en orphan root. La búsqueda por id
        // recorre todos los dirs de op — barato porque el listado es chico (snapshots por
        // operation suelen ser <20).
        val candidates = sequenceOf(operationsRoot, orphanRoot)
            .flatMap { root ->
                if (!Files.isDirectory(root)) emptySequence()
                else Files.list(root).use { it.toList().asSequence() }
            }
            .flatMap { dir ->
                if (Files.isDirectory(dir)) {
                    Files.list(dir.resolve("snapshots")).use { stream ->
                        if (!Files.isDirectory(dir.resolve("snapshots"))) emptySequence()
                        else stream.toList().asSequence()
                    }
                } else emptySequence()
            }
        for (path in candidates) {
            if (path.fileName.toString() == "$id.json") {
                return runCatching { mapper.readValue<Snapshot>(path.toFile()) }.getOrNull()
            }
        }
        return null
    }

    override fun listForOperation(operationId: String): List<Snapshot> {
        val dir = operationsRoot.resolve(operationId).resolve("snapshots")
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(".json") }
                .map { runCatching { mapper.readValue<Snapshot>(it.toFile()) }.getOrNull() }
                .filter { it != null }.map { it!! }.toList()
        }
    }

    override fun listForDevice(operationId: String?, deviceAlias: String?, sessionId: String?): List<Snapshot> {
        val base = if (operationId != null) listForOperation(operationId) else allSnapshots()
        return base.filter { s ->
            (deviceAlias == null || s.deviceAlias == deviceAlias) &&
                (sessionId == null || s.sessionId == sessionId)
        }
    }

    private fun allSnapshots(): List<Snapshot> {
        val out = mutableListOf<Snapshot>()
        for (root in listOf(operationsRoot, orphanRoot)) {
            if (!Files.isDirectory(root)) continue
            Files.list(root).use { stream ->
                for (dir in stream.toList()) {
                    if (!Files.isDirectory(dir)) continue
                    val snapDir = if (dir == orphanRoot) dir else dir.resolve("snapshots")
                    if (!Files.isDirectory(snapDir)) continue
                    Files.list(snapDir).use { fileStream ->
                        for (file in fileStream.toList()) {
                            if (file.fileName.toString().endsWith(".json")) {
                                runCatching { mapper.readValue<Snapshot>(file.toFile()) }
                                    .getOrNull()?.let { out += it }
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    private fun dirFor(operationId: String?): Path =
        if (operationId.isNullOrBlank()) orphanRoot
        else operationsRoot.resolve(operationId).resolve("snapshots")

    companion object {
        fun defaultOperationsRoot(): Path =
            Path.of(System.getProperty("user.home"), ".opentermx", "operations")

        fun defaultOrphanRoot(): Path =
            Path.of(System.getProperty("user.home"), ".opentermx", "snapshots")
    }
}

/** Impl in-memory para tests; sin disco. */
class InMemorySnapshotStore : SnapshotStore {
    private val byId = mutableMapOf<String, Snapshot>()

    @Synchronized override fun save(snapshot: Snapshot) {
        byId[snapshot.id] = snapshot
    }

    @Synchronized override fun load(id: String): Snapshot? = byId[id]

    @Synchronized override fun listForOperation(operationId: String): List<Snapshot> =
        byId.values.filter { it.operationId == operationId }

    @Synchronized override fun listForDevice(
        operationId: String?, deviceAlias: String?, sessionId: String?,
    ): List<Snapshot> = byId.values.filter { s ->
        (operationId == null || s.operationId == operationId) &&
            (deviceAlias == null || s.deviceAlias == deviceAlias) &&
            (sessionId == null || s.sessionId == sessionId)
    }
}
