package com.opentermx.logger

import com.opentermx.common.event.ConnectionEvent
import com.opentermx.common.event.EventBus
import com.opentermx.common.event.Subscription

class SessionLogger(
    val sessionId: String,
    val config: LogConfig,
) : AutoCloseable {

    private val writer: LogWriter = LogWriter.create(config)
    private val subscription: Subscription = EventBus.subscribe { event ->
        if (event is ConnectionEvent.DataReceived && event.sessionId == sessionId) {
            writer.writeBytes(event.data, 0, event.length)
            writer.flush()
        }
    }

    override fun close() {
        try { subscription.close() } catch (_: Exception) {}
        try { writer.close() } catch (_: Exception) {}
    }
}