package com.opentermx.app.inventory

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

data class InventoryImportRow(
    val rowNumber: Int,
    val ip: String,
    val name: String,
    val username: String,
    internal val password: String,
    val vendor: String,
    val model: String,
    val deviceType: String,
    val mac: String?,
    val serialNumber: String?,
    val port: Int,
    val protocol: String,
) {
    override fun toString(): String =
        "InventoryImportRow(rowNumber=$rowNumber, ip=$ip, name=$name, username=$username, " +
            "password=<REDACTED>, vendor=$vendor, model=$model, deviceType=$deviceType, " +
            "mac=$mac, serialNumber=$serialNumber, port=$port, protocol=$protocol)"
}

data class InventoryImportIssue(val rowNumber: Int, val field: String, val message: String)

data class InventoryImportPreview(
    val rows: List<InventoryImportRow>,
    val issues: List<InventoryImportIssue>,
) {
    val valid: Boolean get() = issues.isEmpty()
    val acceptedCount: Int get() = rows.size - issues.map { it.rowNumber }.filter { it > 0 }.toSet().size
}

/** Lee XLSX o CSV UTF-8 sin ejecutar fórmulas ni macros. */
object InventoryExcelImporter {
    private const val MAX_FILE_BYTES = 20L * 1024 * 1024
    private const val MAX_ROWS = 10_000
    private val required = setOf("ip", "nombre", "usuario", "clave", "marca", "modelo", "tipo_dispositivo")

    fun preview(path: Path): InventoryImportPreview {
        require(Files.isRegularFile(path)) { "archivo no encontrado" }
        require(Files.size(path) <= MAX_FILE_BYTES) { "el archivo excede 20 MB" }
        return when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
            "xlsx" -> previewXlsx(path)
            "csv" -> previewMatrix(parseCsv(Files.readString(path, Charsets.UTF_8)))
            else -> throw IllegalArgumentException("se requiere un archivo .xlsx o .csv UTF-8")
        }
    }

    private fun previewXlsx(path: Path): InventoryImportPreview {
        ZipFile(path.toFile()).use { zip ->
            val shared = zip.getEntry("xl/sharedStrings.xml")?.let { entry ->
                zip.getInputStream(entry).use { input ->
                    val doc = secureFactory().newDocumentBuilder().parse(input)
                    elements(doc.documentElement, "si").map { si ->
                        elements(si, "t").joinToString("") { it.textContent }
                    }
                }
            }.orEmpty()
            val sheet = zip.getEntry("xl/worksheets/sheet1.xml")
                ?: throw IllegalArgumentException("el libro no contiene la primera hoja")
            val matrix = zip.getInputStream(sheet).use { input ->
                val doc = secureFactory().newDocumentBuilder().parse(input)
                elements(doc.documentElement, "row").take(MAX_ROWS + 2).map { row ->
                    val values = linkedMapOf<Int, String>()
                    elements(row, "c").forEach { cell ->
                        val ref = cell.getAttribute("r")
                        val col = columnIndex(ref.takeWhile(Char::isLetter))
                        val type = cell.getAttribute("t")
                        val raw = when (type) {
                            "inlineStr" -> elements(cell, "t").joinToString("") { it.textContent }
                            else -> elements(cell, "v").firstOrNull()?.textContent.orEmpty()
                        }
                        values[col] = if (type == "s") shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty() else raw
                    }
                    values
                }
            }
            require(matrix.isNotEmpty()) { "la primera hoja está vacía" }
            require(matrix.size <= MAX_ROWS + 1) { "el archivo excede $MAX_ROWS filas" }
            return previewMatrix(matrix)
        }
    }

    private fun previewMatrix(matrix: List<Map<Int, String>>): InventoryImportPreview {
            require(matrix.isNotEmpty()) { "el archivo está vacío" }
            require(matrix.size <= MAX_ROWS + 1) { "el archivo excede $MAX_ROWS filas" }
            val headers = matrix.first().mapValues { normalizeHeader(it.value.removePrefix("\uFEFF")) }
            val byName = headers.entries.associate { it.value to it.key }
            val missing = required - byName.keys
            if (missing.isNotEmpty()) {
                return InventoryImportPreview(emptyList(), missing.sorted().map {
                    InventoryImportIssue(1, it, "columna requerida ausente")
                })
            }
            val rows = mutableListOf<InventoryImportRow>()
            val issues = mutableListOf<InventoryImportIssue>()
            matrix.drop(1).forEachIndexed { index, cells ->
                val rowNumber = index + 2
                fun value(name: String) = cells[byName[name]]?.trim().orEmpty()
                if (cells.values.all { it.isBlank() }) return@forEachIndexed
                val ip = value("ip")
                val name = value("nombre")
                val username = value("usuario")
                val password = value("clave")
                val vendor = value("marca")
                val model = value("modelo")
                val type = value("tipo_dispositivo")
                listOf("ip" to ip, "nombre" to name, "usuario" to username, "clave" to password,
                    "marca" to vendor, "modelo" to model, "tipo_dispositivo" to type).forEach { (field, v) ->
                    if (v.isBlank()) issues += InventoryImportIssue(rowNumber, field, "valor requerido")
                }
                if (!validIp(ip)) issues += InventoryImportIssue(rowNumber, "ip", "dirección IP inválida")
                val mac = value("mac").takeIf(String::isNotBlank)?.let { normalizeMac(it) }
                if (value("mac").isNotBlank() && mac == null) issues += InventoryImportIssue(rowNumber, "mac", "MAC inválida")
                val port = value("puerto").ifBlank { "22" }.toIntOrNull()
                if (port == null || port !in 1..65535) issues += InventoryImportIssue(rowNumber, "puerto", "puerto inválido")
                val protocol = value("protocolo").ifBlank { "SSH" }.uppercase()
                if (protocol !in setOf("SSH", "TELNET")) {
                    issues += InventoryImportIssue(rowNumber, "protocolo", "debe ser SSH o TELNET")
                }
                rows += InventoryImportRow(rowNumber, ip, name, username, password, vendor, model, type,
                    mac, value("numero_serie").takeIf(String::isNotBlank), port ?: 22, protocol)
            }
            markDuplicates(rows, issues)
            return InventoryImportPreview(rows, issues)
    }

    private fun parseCsv(text: String): List<Map<Int, String>> {
        val rows = mutableListOf<Map<Int, String>>()
        var current = StringBuilder()
        var fields = mutableListOf<String>()
        var quoted = false
        var i = 0
        fun finishField() { fields += current.toString(); current = StringBuilder() }
        fun finishRow() {
            finishField()
            rows += fields.mapIndexed { index, value -> index to value }.toMap()
            fields = mutableListOf()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { current.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> finishField()
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    finishRow()
                    if (rows.size > MAX_ROWS + 1) throw IllegalArgumentException("el archivo excede $MAX_ROWS filas")
                }
                else -> current.append(c)
            }
            i++
        }
        require(!quoted) { "CSV inválido: comillas sin cerrar" }
        if (current.isNotEmpty() || fields.isNotEmpty()) finishRow()
        return rows
    }

    private fun markDuplicates(rows: List<InventoryImportRow>, issues: MutableList<InventoryImportIssue>) {
        fun duplicates(key: (InventoryImportRow) -> String?) = rows.groupBy(key).filter { it.key != null && it.value.size > 1 }
        duplicates { it.serialNumber?.lowercase() }.values.flatten().forEach {
            issues += InventoryImportIssue(it.rowNumber, "numero_serie", "duplicado en el archivo")
        }
        duplicates { it.mac }.values.flatten().forEach {
            issues += InventoryImportIssue(it.rowNumber, "mac", "duplicada en el archivo")
        }
        duplicates { "${it.ip}:${it.port}" }.values.flatten().forEach {
            issues += InventoryImportIssue(it.rowNumber, "ip", "IP y puerto duplicados en el archivo")
        }
    }

    private fun validIp(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 && it == (it.toIntOrNull()?.toString()) }) return true
        return value.contains(':') && value.matches(Regex("[0-9A-Fa-f:]+"))
    }

    private fun normalizeMac(value: String): String? {
        val hex = value.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.lowercase()
        return hex.takeIf { it.length == 12 }?.chunked(2)?.joinToString(":")
    }

    private fun normalizeHeader(value: String): String = java.text.Normalizer.normalize(value.trim().lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(' ', '_').let {
            when (it) { "password", "contrasena" -> "clave"; "hostname" -> "nombre"; "serial", "serial_number" -> "numero_serie"; else -> it }
        }

    private fun columnIndex(letters: String): Int = letters.fold(0) { acc, c -> acc * 26 + (c.uppercaseChar() - 'A' + 1) } - 1

    private fun elements(root: Element, localName: String): List<Element> {
        val nodes = root.getElementsByTagNameNS("*", localName)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun secureFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
}
