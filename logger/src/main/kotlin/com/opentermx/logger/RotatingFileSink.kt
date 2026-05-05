package com.opentermx.logger

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

class RotatingFileSink(
    private val basePath: Path,
    private val extension: String,
    private val rotation: RotationPolicy,
) : AutoCloseable {

    private var stream: OutputStream
    private var bytesWritten = AtomicLong(0)
    private var openedAt: Long = System.currentTimeMillis()
    private var rotationIndex: Int = 0
    private var onRotateHeader: (OutputStream) -> Unit = {}
    private var onRotateFooter: (OutputStream) -> Unit = {}
    private var currentPath: Path = nextPath(initial = true)

    init {
        Files.createDirectories(basePath.toAbsolutePath().parent ?: Path.of("."))
        stream = BufferedOutputStream(Files.newOutputStream(currentPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
    }

    fun setHooks(header: (OutputStream) -> Unit, footer: (OutputStream) -> Unit) {
        onRotateHeader = header
        onRotateFooter = footer
        header(stream)
    }

    @Synchronized
    fun write(data: ByteArray, offset: Int, length: Int) {
        rotateIfNeeded(length.toLong())
        stream.write(data, offset, length)
        bytesWritten.addAndGet(length.toLong())
    }

    @Synchronized
    fun flush() {
        stream.flush()
    }

    val currentFile: Path get() = currentPath

    @Synchronized
    private fun rotateIfNeeded(incoming: Long) {
        val needRotation = when (val r = rotation) {
            RotationPolicy.None -> false
            is RotationPolicy.BySize -> bytesWritten.get() + incoming > r.maxBytes
            is RotationPolicy.ByTime -> System.currentTimeMillis() - openedAt > r.intervalMillis
        }
        if (needRotation) rotate()
    }

    @Synchronized
    private fun rotate() {
        try { onRotateFooter(stream) } catch (_: Exception) {}
        stream.flush()
        stream.close()
        rotationIndex++
        currentPath = nextPath(initial = false)
        stream = BufferedOutputStream(Files.newOutputStream(currentPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
        bytesWritten.set(0)
        openedAt = System.currentTimeMillis()
        try { onRotateHeader(stream) } catch (_: Exception) {}
    }

    private fun nextPath(initial: Boolean): Path {
        if (rotation is RotationPolicy.None && initial) return resolveBase()
        val parent = basePath.toAbsolutePath().parent ?: Path.of(".")
        val filename = basePath.fileName.toString()
        val stem = if (filename.endsWith(".$extension")) filename.removeSuffix(".$extension") else filename
        val suffix = when (rotation) {
            is RotationPolicy.ByTime -> "-" + LocalDateTime.now().format(SUFFIX_FMT)
            is RotationPolicy.BySize -> "-%04d".format(rotationIndex)
            RotationPolicy.None -> ""
        }
        return parent.resolve("$stem$suffix.$extension")
    }

    private fun resolveBase(): Path {
        val filename = basePath.fileName.toString()
        return if (filename.endsWith(".$extension")) basePath
        else basePath.parent?.resolve("$filename.$extension")
            ?: Path.of("$filename.$extension")
    }

    @Synchronized
    override fun close() {
        try { onRotateFooter(stream) } catch (_: Exception) {}
        try { stream.flush() } catch (_: Exception) {}
        try { stream.close() } catch (_: Exception) {}
    }

    companion object {
        private val SUFFIX_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}