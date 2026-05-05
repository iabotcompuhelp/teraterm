package com.opentermx.logger

class RawLogWriter(private val config: LogConfig) : LogWriter {

    private val sink = RotatingFileSink(config.basePath, config.format.extension, config.rotation)

    override fun writeBytes(data: ByteArray, offset: Int, length: Int) {
        sink.write(data, offset, length)
    }

    override fun flush() = sink.flush()
    override fun close() = sink.close()
}