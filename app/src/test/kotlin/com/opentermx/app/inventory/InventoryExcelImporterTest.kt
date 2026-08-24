package com.opentermx.app.inventory

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InventoryExcelImporterTest {
    @TempDir lateinit var root: Path

    @Test
    fun `preview normaliza mac y no expone clave en resultado`() {
        val file = workbook(listOf(
            listOf("ip", "nombre", "usuario", "clave", "marca", "modelo", "tipo dispositivo", "mac", "numero serie"),
            listOf("10.0.0.10", "sw-core", "admin", "secreto-123", "Aruba", "JL660A", "switch", "AA-BB-CC-DD-EE-FF", "CN001"),
        ))
        val preview = InventoryExcelImporter.preview(file)
        assertTrue(preview.valid, preview.issues.toString())
        assertEquals("aa:bb:cc:dd:ee:ff", preview.rows.single().mac)
        assertEquals(22, preview.rows.single().port)
        assertFalse(preview.toString().contains("secreto-123"))
    }

    @Test
    fun `preview rechaza duplicados y datos invalidos`() {
        val file = workbook(listOf(
            listOf("ip", "nombre", "usuario", "clave", "marca", "modelo", "tipo_dispositivo", "mac"),
            listOf("10.0.0.999", "sw-a", "admin", "x", "Aruba", "JL660A", "switch", "bad"),
            listOf("10.0.0.999", "sw-b", "admin", "y", "Aruba", "JL660A", "switch", "bad"),
        ))
        val preview = InventoryExcelImporter.preview(file)
        assertFalse(preview.valid)
        assertTrue(preview.issues.any { it.field == "ip" && it.message.contains("inválida") })
        assertTrue(preview.issues.any { it.message.contains("duplicados") })
    }

    @Test
    fun `preview acepta csv utf8 con campos entre comillas`() {
        val file = root.resolve("inventory.csv")
        Files.writeString(file, "\uFEFFip,nombre,usuario,clave,marca,modelo,tipo_dispositivo,mac,numero_serie,puerto,protocolo\n" +
            "10.0.0.20,\"switch, piso 2\",admin,secreto,Aruba,JL660A,switch,AA:BB:CC:DD:EE:20,CN020,22,SSH\n")
        val preview = InventoryExcelImporter.preview(file)
        assertTrue(preview.valid, preview.issues.toString())
        assertEquals("switch, piso 2", preview.rows.single().name)
        assertEquals("aa:bb:cc:dd:ee:20", preview.rows.single().mac)
        assertFalse(preview.toString().contains("secreto"))
    }

    private fun workbook(rows: List<List<String>>): Path {
        val path = root.resolve("inventory.xlsx")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            val xml = buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
                rows.forEachIndexed { rowIndex, row ->
                    append("<row r=\"${rowIndex + 1}\">")
                    row.forEachIndexed { col, value ->
                        append("<c r=\"${column(col)}${rowIndex + 1}\" t=\"inlineStr\"><is><t>")
                        append(value.replace("&", "&amp;").replace("<", "&lt;"))
                        append("</t></is></c>")
                    }
                    append("</row>")
                }
                append("</sheetData></worksheet>")
            }
            zip.write(xml.toByteArray())
            zip.closeEntry()
        }
        return path
    }

    private fun column(index: Int): String {
        var n = index + 1
        var result = ""
        while (n > 0) { n--; result = ('A' + n % 26) + result; n /= 26 }
        return result
    }
}
