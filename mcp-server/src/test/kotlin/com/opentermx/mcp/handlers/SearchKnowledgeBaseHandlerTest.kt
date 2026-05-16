package com.opentermx.mcp.handlers

import com.opentermx.ai.rag.KnowledgeBase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SearchKnowledgeBaseHandlerTest {

    @TempDir
    lateinit var tmp: Path

    private lateinit var kb: KnowledgeBase

    @BeforeEach
    fun setup() {
        kb = KnowledgeBase(tmp.resolve("idx"))
    }

    @AfterEach
    fun teardown() {
        runCatching { kb.close() }
    }

    @Test
    fun `provider null devuelve hits vacíos sin lanzar`() = runBlocking {
        val handler = SearchKnowledgeBaseHandler { null }
        val result = handler.invoke(mapOf("query" to "anything"))
        @Suppress("UNCHECKED_CAST")
        val hits = result["hits"] as List<Map<String, Any?>>
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `KB vacía devuelve hits vacíos`() = runBlocking {
        val handler = SearchKnowledgeBaseHandler { kb }
        val result = handler.invoke(mapOf("query" to "vlan"))
        @Suppress("UNCHECKED_CAST")
        val hits = result["hits"] as List<Map<String, Any?>>
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `query devuelve chunks indexados con metadata`() = runBlocking {
        val doc = tmp.resolve("notes.md")
        Files.writeString(
            doc,
            """
            # Política de VLANs
            La VLAN 10 es para gestión.
            La VLAN 20 es para usuarios.
            La VLAN 30 es para servidores.
            """.trimIndent()
        )
        kb.addDocument(doc)
        val handler = SearchKnowledgeBaseHandler { kb }
        val result = handler.invoke(mapOf("query" to "VLAN gestión", "topK" to 3))
        @Suppress("UNCHECKED_CAST")
        val hits = result["hits"] as List<Map<String, Any?>>
        assertTrue(hits.isNotEmpty(), "Esperaba al menos un hit, vino $hits")
        val first = hits.first()
        assertTrue((first["text"] as String).contains("VLAN", ignoreCase = true))
        assertTrue((first["score"] as Double) > 0.0)
        assertEquals(doc.toAbsolutePath().toString(), first["source"])
    }
}