package com.opentermx.app.ui.ai

import com.opentermx.ai.rag.KnowledgeBase
import com.opentermx.app.settings.AiAssistantSettings
import java.nio.file.Path

/**
 * Singleton lazy del [KnowledgeBase] compartido por:
 *   - el tab "Knowledge Base" del diálogo Setup → AI Assistant
 *   - el [AiChatPanel] al inyectar `{rag_context}` antes de cada prompt
 *
 * El índice persiste en `~/.opentermx/knowledge/index/`. La instancia se cierra en
 * `MainWindow.setOnCloseRequest`.
 */
object KnowledgeBaseHolder {

    private var instance: KnowledgeBase? = null
    private val lock = Any()

    fun get(settings: AiAssistantSettings): KnowledgeBase {
        synchronized(lock) {
            val current = instance
            if (current != null) {
                current.setChunkParameters(settings.ragChunkSize, settings.ragChunkOverlap)
                return current
            }
            val dir = Path.of(System.getProperty("user.home"), ".opentermx", "knowledge", "index")
            val kb = KnowledgeBase(dir, settings.ragChunkSize, settings.ragChunkOverlap)
            kb.hydrateFromPaths(settings.knowledgeBaseFiles)
            instance = kb
            return kb
        }
    }

    fun shutdown() {
        synchronized(lock) {
            instance?.close()
            instance = null
        }
    }
}
