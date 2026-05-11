package com.opentermx.ai.rag

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipInputStream
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

/**
 * Carga un documento desde disco y devuelve su contenido como texto plano UTF-8.
 * Implementaciones cubren los formatos soportados por la spec v4 § Knowledge Base:
 * `.txt`, `.md`, `.pdf`, `.docx`.
 */
fun interface DocumentLoader {
    fun load(path: Path): String
}

/**
 * Selecciona el [DocumentLoader] adecuado según la extensión del fichero. Lanza
 * [UnsupportedDocumentFormatException] si el formato no es soportado.
 */
object DocumentLoaders {

    fun loaderFor(path: Path): DocumentLoader {
        val ext = path.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "txt", "md", "markdown", "log", "cfg", "conf" -> TextDocumentLoader
            "pdf" -> PdfDocumentLoader
            "docx" -> DocxDocumentLoader
            else -> throw UnsupportedDocumentFormatException(ext.ifBlank { "unknown" })
        }
    }

    val SUPPORTED_EXTENSIONS = listOf("txt", "md", "markdown", "pdf", "docx")
}

class UnsupportedDocumentFormatException(val extension: String) :
    RuntimeException("Formato de documento no soportado: .$extension")

object TextDocumentLoader : DocumentLoader {
    override fun load(path: Path): String = Files.readString(path, Charsets.UTF_8)
}

/**
 * Lector PDF usando Apache PDFBox 3.x. PDFBox 3 cambió la API: el `Loader.loadPDF(File)`
 * sustituye a `PDDocument.load`. El stripper devuelve el texto completo del documento.
 */
object PdfDocumentLoader : DocumentLoader {
    override fun load(path: Path): String {
        Loader.loadPDF(path.toFile()).use { doc ->
            val stripper = PDFTextStripper()
            return stripper.getText(doc)
        }
    }
}

/**
 * Lector DOCX minimalista: un `.docx` es un ZIP. El contenido principal está en
 * `word/document.xml`. Extraemos los nodos `<w:t>…</w:t>` sin parsear el XML completo —
 * suficiente para text retrieval (tablas/listas pierden estructura pero el texto se
 * preserva). No requiere Apache POI (~15 MB de deps).
 */
object DocxDocumentLoader : DocumentLoader {

    private val TEXT_NODE = Regex("""<w:t[^>]*>([^<]*)</w:t>""")
    private val PARAGRAPH_END = Regex("""</w:p>""")

    override fun load(path: Path): String {
        ZipInputStream(Files.newInputStream(path)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    return extractText(xml)
                }
                entry = zip.nextEntry
            }
        }
        return ""
    }

    private fun extractText(xml: String): String {
        // Inserta saltos de línea entre párrafos antes de extraer los <w:t>
        val withParagraphs = xml.replace(PARAGRAPH_END, "</w:p>\n")
        val sb = StringBuilder()
        TEXT_NODE.findAll(withParagraphs).forEach { match ->
            sb.append(unescapeXml(match.groupValues[1]))
            sb.append(' ')
        }
        return sb.toString()
            .replace(Regex(" {2,}"), " ")
            .replace(Regex("\n+"), "\n")
            .trim()
    }

    private fun unescapeXml(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
}
