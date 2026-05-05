package com.opentermx.logger

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class HtmlLogWriter(private val config: LogConfig) : LogWriter {

    private val sink = RotatingFileSink(config.basePath, config.format.extension, config.rotation)
    private val converter = AnsiHtmlConverter()
    private val tsFormatter: DateTimeFormatter? =
        if (config.timestamps) DateTimeFormatter.ofPattern(config.timestampPattern) else null
    private var atLineStart = true

    init {
        sink.setHooks(
            header = ::writeHeader,
            footer = ::writeFooter,
        )
    }

    override fun writeBytes(data: ByteArray, offset: Int, length: Int) {
        val text = String(data, offset, length, StandardCharsets.UTF_8)
        val converted = converter.convertChunk(text)
        if (converted.isEmpty()) return
        val out = StringBuilder(converted.length + 32)
        for (c in converted) {
            if (atLineStart && c != '\n') {
                tsFormatter?.let { out.append("<span class=\"ts\">[").append(it.format(LocalDateTime.now())).append("] </span>") }
                atLineStart = false
            }
            out.append(c)
            if (c == '\n') atLineStart = true
        }
        val bytes = out.toString().toByteArray(StandardCharsets.UTF_8)
        sink.write(bytes, 0, bytes.size)
    }

    override fun flush() = sink.flush()

    override fun close() {
        val tail = converter.closingTags()
        if (tail.isNotEmpty()) {
            val tailBytes = tail.toByteArray(StandardCharsets.UTF_8)
            sink.write(tailBytes, 0, tailBytes.size)
        }
        sink.close()
    }

    private fun writeHeader(stream: OutputStream) {
        stream.write(HEADER.toByteArray(StandardCharsets.UTF_8))
        atLineStart = true
    }

    private fun writeFooter(stream: OutputStream) {
        val tail = converter.closingTags()
        if (tail.isNotEmpty()) stream.write(tail.toByteArray(StandardCharsets.UTF_8))
        stream.write(FOOTER.toByteArray(StandardCharsets.UTF_8))
    }

    companion object {
        private const val HEADER = "<!DOCTYPE html>\n<html><head><meta charset=\"UTF-8\">" +
            "<style>body{background:#0f0f10;color:#e6e6e6;font-family:monospace;margin:0;padding:8px;}" +
            "pre{margin:0;white-space:pre-wrap;}.ts{color:#888;}</style></head><body><pre>\n"
        private const val FOOTER = "\n</pre></body></html>\n"
    }
}