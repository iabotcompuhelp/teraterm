package com.opentermx.ai.parse

/**
 * Extrae bloques de código markdown (` ```lang … ``` `) de una respuesta de la IA.
 *
 * Heurística de explicación: el texto natural inmediatamente anterior al bloque (hasta
 * el bloque previo o el inicio) se asocia como explicación. Si la IA puso la explicación
 * después del bloque (como en la spec § 461), se concatena lo que haya entre este bloque
 * y el siguiente cierre de bloque o final.
 *
 * Líneas vacías y comentarios (`!`, `#`) dentro del bloque se conservan — el clasificador
 * de riesgo las trata como SAFE.
 */
object CodeBlockParser {

    private val FENCE = Regex("""(?m)^\s*```([\w-]*)\s*$""")

    fun parse(response: String): List<CodeBlock> {
        if (response.isBlank() || "```" !in response) return emptyList()
        val matches = FENCE.findAll(response).toList()
        if (matches.size < 2) return emptyList()

        val blocks = mutableListOf<CodeBlock>()
        var prevEnd = 0
        var i = 0
        while (i < matches.size - 1) {
            val open = matches[i]
            val close = matches[i + 1]
            // Texto entre el cierre anterior (o inicio) y este open ⇒ explicación previa.
            val before = response.substring(prevEnd, open.range.first).trim()
            // Contenido del bloque
            val rawContent = response.substring(open.range.last + 1, close.range.first)
            val lines = rawContent.lines()
                // Recorta el primer/último newline residual del split sin perder indentación interna
                .let { if (it.firstOrNull() == "") it.drop(1) else it }
                .let { if (it.lastOrNull() == "") it.dropLast(1) else it }
                .filter { it.isNotBlank() || true } // mantener exactos; el filtro lo decide la UI
                .map { it.trimEnd() }
            val lang = open.groupValues[1]
            // Texto entre este close y el siguiente open (o final del texto) ⇒ posible explicación posterior
            val nextOpen = matches.getOrNull(i + 2)
            val afterEnd = nextOpen?.range?.first ?: response.length
            val after = response.substring(close.range.last + 1, afterEnd).trim()
            val explanation = listOf(before, after).filter { it.isNotBlank() }.joinToString("\n\n")
            blocks += CodeBlock(lines = lines, language = lang, explanation = explanation)
            prevEnd = close.range.last + 1
            i += 2
        }
        return blocks
    }

    /**
     * Devuelve el texto narrativo (sin bloques de código) de una respuesta — útil para
     * mostrar la "explicación general" cuando hay un solo bloque o ninguno.
     */
    fun narrativeOnly(response: String): String {
        if ("```" !in response) return response.trim()
        return FENCE.split(response)
            .filterIndexed { idx, _ -> idx % 2 == 0 } // tomamos las "afueras" pares
            .joinToString("\n\n") { it.trim() }
            .trim()
    }
}
