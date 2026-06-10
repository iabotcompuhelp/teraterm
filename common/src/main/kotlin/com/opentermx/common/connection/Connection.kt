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

/**
 * Callback de datos entrantes de una conexión.
 *
 * Contrato de ownership: `data` pertenece al receptor. Cada invocación entrega un
 * array fresco que la conexión no vuelve a tocar, así que es seguro diferirlo a otro
 * thread (FX/EDT, colas, etc.) sin copiar. Sólo los primeros [length] bytes son
 * válidos — usar siempre `length`, no `data.size`.
 *
 * El callback corre en el thread de lectura de la conexión: no bloquear acá, o la
 * sesión deja de drenar el socket.
 */
fun interface DataHandler {
    fun onData(data: ByteArray, length: Int)
}

fun interface StateHandler {
    fun onStateChange(state: ConnectionState, error: Throwable?)
}