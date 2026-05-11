package com.opentermx.ai.rag

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KnowledgeBaseTest {

    @Test
    fun indexesAndSearchesTextFile(@TempDir dir: Path) {
        val txt = dir.resolve("vlan-policies.txt")
        Files.writeString(
            txt,
            """
            VLAN 30 está asignada al departamento de Contabilidad.
            Todas las interfaces de acceso deben llevar port-security max 2.
            Los switches Cisco IOS se gestionan vía la VLAN 99.
            """.trimIndent(),
        )
        KnowledgeBase(dir.resolve("index"), chunkSize = 100, chunkOverlap = 10).use { kb ->
            val file = kb.addDocument(txt)
            assertEquals(1, file.chunkCount)
            assertEquals(null, file.error)

            val results = kb.search("VLAN 30 Contabilidad", topK = 3)
            assertTrue(results.isNotEmpty())
            assertTrue(results[0].chunk.text.contains("Contabilidad"))
            assertTrue(results[0].score > 0f)
        }
    }

    @Test
    fun summaryReflectsAddRemoveCycle(@TempDir dir: Path) {
        val a = dir.resolve("a.txt").also { Files.writeString(it, "alpha beta gamma") }
        val b = dir.resolve("b.txt").also { Files.writeString(it, "delta epsilon zeta") }
        KnowledgeBase(dir.resolve("index")).use { kb ->
            kb.addDocument(a)
            kb.addDocument(b)
            assertEquals(2, kb.summary().totalDocuments)
            assertEquals(2, kb.summary().totalChunks)
            kb.removeDocument(a.toAbsolutePath().toString())
            assertEquals(1, kb.summary().totalDocuments)
            val r = kb.search("delta", topK = 3)
            assertEquals(1, r.size)
            assertTrue(r[0].chunk.text.contains("delta"))
        }
    }

    @Test
    fun reindexAllClearsThenRebuilds(@TempDir dir: Path) {
        // Tokens lo bastante distintos para que StandardAnalyzer no los confunda
        // tras separar por puntuación (a/b comparten "unique" y "word" si usamos
        // guiones, así que evitamos eso).
        val a = dir.resolve("a.txt").also { Files.writeString(it, "policy alpha vlanthirty cisco") }
        val b = dir.resolve("b.txt").also { Files.writeString(it, "policy beta vlanfifty juniper") }
        KnowledgeBase(dir.resolve("index")).use { kb ->
            kb.addDocument(a)
            kb.addDocument(b)
            val summary = kb.reindexAll(listOf(a))
            assertEquals(1, summary.totalDocuments)
            assertEquals(0, kb.search("juniper").size)
            assertEquals(0, kb.search("vlanfifty").size)
            val res = kb.search("vlanthirty")
            assertEquals(1, res.size)
        }
    }

    @Test
    fun searchOnEmptyIndexReturnsEmpty(@TempDir dir: Path) {
        KnowledgeBase(dir.resolve("index")).use { kb ->
            assertEquals(0, kb.search("anything").size)
        }
    }

    @Test
    fun handlesUnsupportedFormatGracefully(@TempDir dir: Path) {
        val bad = dir.resolve("data.bin").also { Files.writeString(it, "binary-ish") }
        KnowledgeBase(dir.resolve("index")).use { kb ->
            val result = kb.addDocument(bad)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("Formato"))
            assertEquals(0, result.chunkCount)
        }
    }
}
