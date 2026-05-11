package com.opentermx.ai.rag

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextChunkerTest {

    @Test
    fun returnsSingleChunkWhenTextFitsInSize() {
        val text = "alpha beta gamma delta"
        val chunks = TextChunker.chunk(text, size = 10, overlap = 2)
        assertEquals(1, chunks.size)
        assertEquals("alpha beta gamma delta", chunks[0])
    }

    @Test
    fun returnsMultipleChunksWithOverlap() {
        val words = (1..120).map { "word$it" }
        val text = words.joinToString(" ")
        val chunks = TextChunker.chunk(text, size = 50, overlap = 10)
        // step = 40 → chunks empiezan en 0, 40, 80 → 3 chunks, último tamaño = 40
        assertEquals(3, chunks.size)
        // Solapamiento: las palabras 40..49 deben aparecer al final del chunk 0 y al inicio del chunk 1
        val c0 = chunks[0].split(" ")
        val c1 = chunks[1].split(" ")
        assertEquals(50, c0.size)
        assertEquals(c0.subList(40, 50), c1.subList(0, 10))
    }

    @Test
    fun normalizesWhitespaceAndIgnoresEmptyLines() {
        val text = "  hola   mundo  \n\n   adios   \n"
        val chunks = TextChunker.chunk(text, size = 50, overlap = 5)
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].contains("hola"))
        assertTrue(chunks[0].contains("mundo"))
        assertTrue(chunks[0].contains("adios"))
    }

    @Test
    fun emptyTextReturnsEmptyList() {
        assertEquals(0, TextChunker.chunk("", size = 100, overlap = 10).size)
        assertEquals(0, TextChunker.chunk("   \n  \t  ", size = 100, overlap = 10).size)
    }
}
