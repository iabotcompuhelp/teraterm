package com.opentermx.mcp.handlers

import com.opentermx.ai.rag.KnowledgeBase
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions

/**
 * Handler de la tool `search_knowledge_base`. Recibe el [KnowledgeBase] vía un proveedor
 * lambda (cuya implementación real es `KnowledgeBaseHolder.get(settings)` desde el módulo
 * `app`) para no acoplarnos al ciclo de vida del singleton.
 *
 * Si la KB no está disponible (todavía no inicializada) o el índice está vacío, devuelve
 * `hits: []` en lugar de lanzar — el LLM cliente decide qué hacer con un resultado vacío.
 */
class SearchKnowledgeBaseHandler(
    private val kbProvider: () -> KnowledgeBase?,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.SEARCH_KNOWLEDGE_BASE

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val query = Args.requireString(args, "query")
        val topK = Args.optionalInt(
            args, "topK",
            default = ToolDefinitions.DEFAULT_TOP_K,
            min = 1,
            max = ToolDefinitions.MAX_TOP_K,
        )
        val kb = kbProvider() ?: return linkedMapOf("hits" to emptyList<Any?>())
        val hits = kb.search(query, topK).map { result ->
            linkedMapOf<String, Any?>(
                "source" to result.chunk.source,
                "chunkIndex" to result.chunk.chunkIndex,
                "text" to result.chunk.text,
                "score" to result.score.toDouble(),
            )
        }
        return linkedMapOf("hits" to hits)
    }
}