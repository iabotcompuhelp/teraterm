package com.opentermx.logger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RotationTest {

    @Test
    fun rotatesBySize(@TempDir dir: Path) {
        val base = dir.resolve("session.log")
        val sink = RotatingFileSink(base, "log", RotationPolicy.BySize(100))
        repeat(5) {
            val payload = ByteArray(60) { 'A'.code.toByte() }
            sink.write(payload, 0, payload.size)
        }
        sink.close()
        val files = Files.list(dir).use { stream -> stream.toList() }
        assertTrue(files.size >= 2) { "Esperaba >= 2 archivos rotados, hubo: $files" }
    }

    @Test
    fun noRotationKeepsSingleFile(@TempDir dir: Path) {
        val base = dir.resolve("session.log")
        val sink = RotatingFileSink(base, "log", RotationPolicy.None)
        val payload = ByteArray(500) { 'B'.code.toByte() }
        sink.write(payload, 0, payload.size)
        sink.close()
        val files = Files.list(dir).use { stream -> stream.toList() }
        assertEquals(1, files.size) { "Esperaba 1 archivo, hubo: $files" }
        assertEquals(500L, Files.size(files[0]))
    }
}