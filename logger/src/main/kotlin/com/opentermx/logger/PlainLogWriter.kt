package com.opentermx.logger

import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class PlainLogWriter(private val config: LogConfig) : LogWriter {

    private val sink = RotatingFileSink(config.basePath, config.format.extension, config.rotation)
    private val tsFormatter: DateTimeFormatter? =
        if (config.timestamps) DateTimeFormatter.ofPattern(config.timestampPattern) else null
    private var atLineStart = true
    private val ansiStripper = AnsiStripper()

    override fun writeBytes(data: ByteArray, offset: Int, length: Int) {
        val raw = String(data, offset, length, StandardCharsets.UTF_8)
        val stripped = ansiStripper.feed(raw)
        if (stripped.isEmpty()) return
        val out = StringBuilder(stripped.length + 32)
        for (c in stripped) {
            if (atLineStart && c != '\n' && c != '\r') {
                tsFormatter?.let { out.append('[').append(it.format(LocalDateTime.now())).append("] ") }
                atLineStart = false
            }
            out.append(c)
            if (c == '\n') atLineStart = true
        }
        val bytes = out.toString().toByteArray(StandardCharsets.UTF_8)
        sink.write(bytes, 0, bytes.size)
    }

    override fun flush() = sink.flush()
    override fun close() = sink.close()
}

class AnsiStripper {
    private enum class State { GROUND, ESCAPE, CSI, OSC }
    private var state = State.GROUND

    fun feed(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            when (state) {
                State.GROUND -> when (c.code) {
                    0x1B -> state = State.ESCAPE
                    0x07, 0x08 -> {}
                    else -> out.append(c)
                }
                State.ESCAPE -> when (c) {
                    '[' -> state = State.CSI
                    ']' -> state = State.OSC
                    else -> state = State.GROUND
                }
                State.CSI -> if (c.code in 0x40..0x7E) state = State.GROUND
                State.OSC -> if (c.code == 0x07 || c.code == 0x1B) state = State.GROUND
            }
        }
        return out.toString()
    }
}