package com.opentermx.logger

import java.util.concurrent.ConcurrentHashMap

object LogManager {

    private val active = ConcurrentHashMap<String, SessionLogger>()

    fun start(sessionId: String, config: LogConfig): SessionLogger {
        stop(sessionId)
        val logger = SessionLogger(sessionId, config)
        active[sessionId] = logger
        return logger
    }

    fun stop(sessionId: String) {
        active.remove(sessionId)?.close()
    }

    fun isActive(sessionId: String): Boolean = sessionId in active

    fun activeFor(sessionId: String): SessionLogger? = active[sessionId]

    fun activeSessions(): Set<String> = active.keys.toSet()

    fun stopAll() {
        active.keys.toList().forEach { stop(it) }
    }
}