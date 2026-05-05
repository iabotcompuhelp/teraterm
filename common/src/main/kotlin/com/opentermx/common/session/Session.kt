package com.opentermx.common.session

import com.opentermx.common.connection.Connection
import com.opentermx.common.connection.ConnectionConfig
import java.util.UUID

@JvmInline
value class SessionId(val value: String) {
    override fun toString(): String = value

    companion object {
        @JvmStatic
        fun random(): SessionId = SessionId(UUID.randomUUID().toString())
    }
}

data class Session(
    val id: SessionId,
    val name: String,
    val config: ConnectionConfig,
    val connection: Connection,
)