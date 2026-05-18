package com.opentermx.mcp.snapshots

/**
 * Diff line-based con agrupación por sección cuando el [deviceType] lo permite.
 *
 * Estrategia simple a propósito: una pasada con LCS-like comparison resulta cara para
 * configs grandes y no agrega valor sobre el diff line-based "antes vs después" — el LLM
 * lee mejor un set de líneas removidas + agregadas que un edit-script complicado.
 *
 * Para Cisco IOS y similares ("hpe_comware", "huawei_vrp") agrupamos por **bloque de
 * sección**: las líneas que empiezan en columna 0 son headers; las que llevan
 * indentación pertenecen al header inmediatamente anterior. Eso facilita que el LLM
 * razone sobre "interface Gi0/1 cambió" en vez de "líneas 47 a 53 cambiaron".
 */
object SnapshotDiffer {

    private val SECTION_DEVICE_TYPES = setOf("cisco_ios", "hpe_comware", "huawei_vrp")

    /**
     * Resultado del diff. `sections` solo viene poblado cuando hay agrupación.
     */
    data class DiffResult(
        val addedLines: List<String>,
        val removedLines: List<String>,
        val identicalLineCount: Int,
        val sections: List<SectionDiff>,
        val summary: String,
    )

    data class SectionDiff(
        val header: String,
        val change: SectionChange,
        val addedLines: List<String>,
        val removedLines: List<String>,
    )

    enum class SectionChange { ADDED, REMOVED, MODIFIED }

    fun diff(before: Snapshot, after: Snapshot, deviceType: String? = null): DiffResult {
        val beforeLines = before.content.lines().filter { it.isNotEmpty() }
        val afterLines = after.content.lines().filter { it.isNotEmpty() }

        if (before.contentHash == after.contentHash) {
            return DiffResult(
                addedLines = emptyList(),
                removedLines = emptyList(),
                identicalLineCount = beforeLines.size,
                sections = emptyList(),
                summary = "Sin cambios (hash idéntico)",
            )
        }

        val beforeSet = beforeLines.toSet()
        val afterSet = afterLines.toSet()
        val added = afterLines.filter { it !in beforeSet }
        val removed = beforeLines.filter { it !in afterSet }
        val identical = beforeLines.count { it in afterSet }

        val sections = if (deviceType in SECTION_DEVICE_TYPES) {
            groupBySections(before.content, after.content)
        } else {
            emptyList()
        }

        val summary = buildString {
            append("+").append(added.size).append(" / -").append(removed.size)
            if (sections.isNotEmpty()) {
                append(" en ").append(sections.size).append(" sección(es)")
            }
        }

        return DiffResult(added, removed, identical, sections, summary)
    }

    /**
     * Agrupa por sección los cambios:
     *  - Headers = líneas que empiezan en col 0 y NO son blank.
     *  - Hijas = líneas con indentación inicial.
     * La sección "(no section)" recolecta líneas sueltas antes del primer header.
     */
    private fun groupBySections(before: String, after: String): List<SectionDiff> {
        val beforeSections = parseSections(before)
        val afterSections = parseSections(after)

        val allHeaders = (beforeSections.keys + afterSections.keys).toList()
        val out = mutableListOf<SectionDiff>()
        for (header in allHeaders) {
            val a = beforeSections[header]
            val b = afterSections[header]
            when {
                a == null && b != null -> out += SectionDiff(header, SectionChange.ADDED, b, emptyList())
                a != null && b == null -> out += SectionDiff(header, SectionChange.REMOVED, emptyList(), a)
                a != null && b != null && a != b -> {
                    val aSet = a.toSet()
                    val bSet = b.toSet()
                    out += SectionDiff(
                        header = header,
                        change = SectionChange.MODIFIED,
                        addedLines = b.filter { it !in aSet },
                        removedLines = a.filter { it !in bSet },
                    )
                }
                // a == b: sin cambio, no se reporta.
            }
        }
        return out
    }

    private fun parseSections(text: String): LinkedHashMap<String, List<String>> {
        val sections = LinkedHashMap<String, MutableList<String>>()
        var currentHeader = "(no section)"
        sections[currentHeader] = mutableListOf()
        for (raw in text.lines()) {
            if (raw.isBlank()) continue
            val isHeader = raw == raw.trimStart() && raw.isNotBlank()
            if (isHeader && raw.isNotEmpty()) {
                currentHeader = raw.trim()
                sections.getOrPut(currentHeader) { mutableListOf() }
            } else {
                sections.getValue(currentHeader) += raw.trimEnd()
            }
        }
        // Devolvemos copia inmutable y descartamos las secciones vacías (típico de la
        // sentinel "(no section)" cuando todas las líneas tienen header).
        return sections.entries
            .filter { it.value.isNotEmpty() }
            .associateTo(LinkedHashMap()) { it.key to it.value.toList() }
    }
}
