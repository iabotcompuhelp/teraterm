package com.opentermx.common.connection

interface Connection : AutoCloseable {
    val id: String
    val config: ConnectionConfig
    val state: ConnectionState

    @Throws(Exception::class)
    fun connect()

    @Throws(Exception::class)
    fun send(data: ByteArray, offset: Int, length: Int)

    @Throws(Exception::class)
    fun send(data: ByteArray) = send(data, 0, data.size)

    fun disconnect()

    override fun close() = disconnect()

    fun setDataHandler(handler: DataHandler?)
    fun getDataHandler(): DataHandler? = null
    fun setStateHandler(handler: StateHandler?)
    fun getStateHandler(): StateHandler? = null
}

fun interface DataHandler {
    fun onData(data: ByteArray, length: Int)
}

fun interface StateHandler {
    fun onStateChange(state: ConnectionState, error: Throwable?)
}