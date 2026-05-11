package com.opentermx.ai.rag

/**
 * Divide texto en chunks superpuestos. El tamaño se expresa en palabras (proxy para tokens):
 * empíricamente 1 token ≈ 0.75 palabras en inglés, ~1 palabra en español. El overlap permite
 * que conceptos que cruzan límites de chunk sigan recuperables.
 */
object TextChunker {

    /**
     * @param size número aproximado de palabras por chunk (defecto 500)
     * @param overlap palabras compartidas entre chunks consecutivos (defecto 50)
     */
    fun chunk(text: String, size: Int = 500, overlap: Int = 50): List<String> {
        require(size > 0) { "size debe ser > 0" }
        require(overlap in 0 until size) { "overlap debe ser >= 0 y < size" }
        val cleaned = normalize(text)
        if (cleaned.isEmpty()) return emptyList()
        val words = cleaned.split(WHITESPACE)
        if (words.size <= size) return listOf(cleaned)
        val step = size - overlap
        val out = ArrayList<String>(words.size / step + 1)
        var i = 0
        while (i < words.size) {
            val end = minOf(i + size, words.size)
            out += words.subList(i, end).joinToString(" ")
            if (end == words.size) break
            i += step
        }
        return out
    }

    private val WHITESPACE = Regex("\\s+")

    private fun normalize(text: String): String =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()
}
