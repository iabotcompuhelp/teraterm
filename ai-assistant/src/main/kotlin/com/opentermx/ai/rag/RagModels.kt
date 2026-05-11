package com.opentermx.ai.rag

/**
 * Un fragmento indexado, con su origen (path) y posición ordinal dentro del fichero.
 */
data class RagChunk(
    val text: String,
    val source: String,
    val chunkIndex: Int,
)

data class RagSearchResult(
    val chunk: RagChunk,
    val score: Float,
)

/**
 * Snapshot del estado del índice — alimenta la UI del diálogo Knowledge Base.
 */
data class IndexSummary(
    val totalDocuments: Int,
    val totalChunks: Int,
    val files: List<IndexedFile>,
)

data class IndexedFile(
    val path: String,
    val chunkCount: Int,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val error: String? = null,
)
