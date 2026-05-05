package com.opentermx.common.connection

interface ConnectionFactory {
    fun supports(type: ConnectionType): Boolean

    @Throws(Exception::class)
    fun create(config: ConnectionConfig): Connection
}