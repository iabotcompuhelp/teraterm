package com.opentermx.ai.parse

/**
 * Un bloque de comandos extraído de una respuesta de la IA.
 *
 * - [lines]: cada línea ya separada y trimmed (preservando indentación cuando es significativa).
 * - [language]: marcador opcional del fenced block (e.g. ` ```cisco `, ` ```bash `, vacío).
 * - [explanation]: texto en lenguaje natural que precede o sigue al bloque, sirve como
 *   resumen para mostrar en el panel de revisión. Vacío si la IA no añadió comentarios.
 */
data class CodeBlock(
    val lines: List<String>,
    val language: String = "",
    val explanation: String = "",
)
