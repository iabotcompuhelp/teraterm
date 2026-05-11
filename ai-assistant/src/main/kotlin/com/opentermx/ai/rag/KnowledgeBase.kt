package com.opentermx.ai.rag

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.IntPoint
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.store.FSDirectory
import org.slf4j.LoggerFactory

/**
 * Motor de Retrieval Augmented Generation embebido — Apache Lucene 9.
 *
 * Indexa el contenido de los ficheros del usuario (txt/md/pdf/docx) troceado en chunks
 * superpuestos. La búsqueda en [search] se basa en BM25 (default de Lucene 9) sobre el
 * campo `text` analizado con [StandardAnalyzer]. Los resultados top-K se devuelven con
 * su texto completo y un score relativo.
 *
 * El [indexDir] se persiste en disco (por defecto `~/.opentermx/knowledge/index/`).
 * Crear una instancia es barato; mantener una sola compartida en el proceso evita abrir
 * y cerrar el `IndexWriter` con cada operación.
 *
 * Thread-safety: `IndexWriter` es thread-safe. `search()` abre un `DirectoryReader` NRT
 * por llamada para ver siempre los últimos cambios.
 */
class KnowledgeBase(
    private val indexDir: Path,
    private var chunkSize: Int = 500,
    private var chunkOverlap: Int = 50,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(javaClass)
    private val analyzer = StandardAnalyzer()
    private val directory: FSDirectory by lazy {
        Files.createDirectories(indexDir)
        FSDirectory.open(indexDir)
    }
    private val writer: IndexWriter by lazy {
        val config = IndexWriterConfig(analyzer).apply {
            openMode = IndexWriterConfig.OpenMode.CREATE_OR_APPEND
        }
        IndexWriter(directory, config)
    }
    /**
     * Tabla auxiliar en memoria: path → metadatos del fichero indexado. Se reconstruye
     * con el primer addDocument tras abrir el índice; sobrevive a la sesión cuando el
     * caller pasa la lista de paths conocidos (typically `AiAssistantSettings.knowledgeBaseFiles`).
     */
    private val filesMeta = AtomicReference<Map<String, IndexedFile>>(emptyMap())

    fun setChunkParameters(size: Int, overlap: Int) {
        require(size > 0 && overlap in 0 until size) { "chunkSize/overlap inválidos" }
        chunkSize = size; chunkOverlap = overlap
    }

    /**
     * Carga, trocea e indexa un fichero. Reemplaza chunks previos del mismo path.
     * Devuelve el [IndexedFile] resultante (con `error` poblado si falló).
     */
    fun addDocument(filePath: Path): IndexedFile {
        val pathKey = filePath.toAbsolutePath().toString()
        val attrs = runCatching { Files.readAttributes(filePath, java.nio.file.attribute.BasicFileAttributes::class.java) }
            .getOrNull()
        val size = attrs?.size() ?: 0L
        val mtime = attrs?.lastModifiedTime()?.toMillis() ?: 0L
        return runCatching {
            removeFromIndex(pathKey)
            val loader = DocumentLoaders.loaderFor(filePath)
            val text = loader.load(filePath)
            val chunks = TextChunker.chunk(text, chunkSize, chunkOverlap)
            chunks.forEachIndexed { idx, chunk ->
                val doc = Document().apply {
                    add(StringField("source", pathKey, Field.Store.YES))
                    add(IntPoint("chunkIndex", idx))
                    add(StoredField("chunkIndex", idx))
                    add(TextField("text", chunk, Field.Store.YES))
                }
                writer.addDocument(doc)
            }
            writer.commit()
            val entry = IndexedFile(pathKey, chunks.size, size, mtime)
            filesMeta.updateAndGet { it + (pathKey to entry) }
            entry
        }.getOrElse { e ->
            log.warn("Fallo indexando {}: {}", pathKey, e.message)
            val entry = IndexedFile(pathKey, 0, size, mtime, error = e.message ?: e.javaClass.simpleName)
            filesMeta.updateAndGet { it + (pathKey to entry) }
            entry
        }
    }

    /**
     * Borra del índice todos los chunks asociados a un path.
     */
    fun removeDocument(filePath: String) {
        val pathKey = Path.of(filePath).toAbsolutePath().toString()
        removeFromIndex(pathKey)
        writer.commit()
        filesMeta.updateAndGet { it - pathKey }
    }

    private fun removeFromIndex(pathKey: String) {
        writer.deleteDocuments(Term("source", pathKey))
    }

    /**
     * Limpia el índice y re-indexa todos los paths suministrados.
     */
    fun reindexAll(filePaths: List<Path>): IndexSummary {
        writer.deleteAll()
        writer.commit()
        filesMeta.set(emptyMap())
        filePaths.forEach { addDocument(it) }
        return summary()
    }

    /**
     * Busca los top-K chunks más relevantes a [query]. Si el índice está vacío devuelve
     * `emptyList`. Errores de parseo de la query (caracteres especiales de Lucene) se
     * absorben aplicando escape automático.
     */
    fun search(query: String, topK: Int = 5): List<RagSearchResult> {
        if (query.isBlank()) return emptyList()
        if (DirectoryReader.indexExists(directory).not()) return emptyList()
        DirectoryReader.open(writer).use { reader ->
            if (reader.numDocs() == 0) return emptyList()
            val searcher = IndexSearcher(reader)
            val parser = QueryParser("text", analyzer)
            val parsed = runCatching { parser.parse(QueryParser.escape(query)) }
                .getOrElse { return emptyList() }
            val topDocs = searcher.search(parsed, topK)
            val storedFields = searcher.storedFields()
            return topDocs.scoreDocs.map { sd ->
                val doc = storedFields.document(sd.doc)
                RagSearchResult(
                    chunk = RagChunk(
                        text = doc.get("text") ?: "",
                        source = doc.get("source") ?: "",
                        chunkIndex = doc.get("chunkIndex")?.toIntOrNull() ?: 0,
                    ),
                    score = sd.score,
                )
            }
        }
    }

    fun summary(): IndexSummary {
        val files = filesMeta.get().values.toList().sortedBy { it.path }
        val totalChunks = files.sumOf { it.chunkCount }
        return IndexSummary(
            totalDocuments = files.size,
            totalChunks = totalChunks,
            files = files,
        )
    }

    /**
     * Sincroniza el catálogo en memoria con la lista persistida en `AiAssistantSettings`
     * sin reindexar — útil al iniciar la app para que la UI muestre algo coherente. Los
     * counts (`chunkCount`) son aproximados hasta el próximo `reindexAll`/`addDocument`.
     */
    fun hydrateFromPaths(paths: List<String>) {
        val map = paths.associateWith { p ->
            val path = Path.of(p)
            val attrs = runCatching { Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java) }
                .getOrNull()
            IndexedFile(
                path = p,
                chunkCount = -1, // desconocido sin reindexar
                sizeBytes = attrs?.size() ?: 0L,
                lastModifiedMillis = attrs?.lastModifiedTime()?.toMillis() ?: 0L,
                error = if (attrs == null) "Fichero no encontrado" else null,
            )
        }
        filesMeta.set(map)
    }

    override fun close() {
        runCatching { writer.close() }
        runCatching { directory.close() }
        runCatching { analyzer.close() }
    }
}
