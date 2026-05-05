package com.opentermx.common.event

import com.opentermx.common.connection.ConnectionState
import java.time.Instant

sealed interface AppEvent {
    val timestamp: Instant
}

sealed interface ConnectionEvent : AppEvent {
    val sessionId: String

    data class StateChanged(
        override val sessionId: String,
        val previous: ConnectionState,
        val current: ConnectionState,
        val error: Throwable? = null,
        override val timestamp: Instant = Instant.now(),
    ) : ConnectionEvent

    data class DataReceived(
        override val sessionId: String,
        val data: ByteArray,
        val length: Int,
        override val timestamp: Instant = Instant.now(),
    ) : ConnectionEvent

    data class DataSent(
        override val sessionId: String,
        val length: Int,
        override val timestamp: Instant = Instant.now(),
    ) : ConnectionEvent
}

sealed interface SessionEvent : AppEvent {
    val sessionId: String

    data class Opened(
        override val sessionId: String,
        val name: String,
        override val timestamp: Instant = Instant.now(),
    ) : SessionEvent

    data class Closed(
        override val sessionId: String,
        override val timestamp: Instant = Instant.now(),
    ) : SessionEvent
}

sealed interface MacroEvent : AppEvent {
    val macroName: String

    data class Started(
        override val macroName: String,
        override val timestamp: Instant = Instant.now(),
    ) : MacroEvent

    data class Finished(
        override val macroName: String,
        val success: Boolean,
        val error: Throwable? = null,
        override val timestamp: Instant = Instant.now(),
    ) : MacroEvent

    data class Output(
        override val macroName: String,
        val message: String,
        override val timestamp: Instant = Instant.now(),
    ) : MacroEvent
}

sealed interface LogEvent : AppEvent {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val sessionId: String?,
        val level: Level,
        val message: String,
        override val timestamp: Instant = Instant.now(),
    ) : LogEvent
}