package com.opentermx.logger

interface LogWriter : AutoCloseable {
    fun writeBytes(data: ByteArray, offset: Int, length: Int)
    fun flush()
    override fun close()

    companion object {
        fun create(config: LogConfig): LogWriter = when (config.format) {
            LogFormat.PLAIN -> PlainLogWriter(config)
            LogFormat.HTML -> HtmlLogWriter(config)
            LogFormat.RAW -> RawLogWriter(config)
        }
    }
}