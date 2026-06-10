package com.opentermx.mcp.security

import java.util.concurrent.ConcurrentHashMap

/**
 * Estado compartido entre el handler `tail_session` y el subscriber EventBus en
 * [com.opentermx.mcp.McpServer]. Lleva el conjunto de sesiones con tail activo y su
 * expiry timestamp para enforcear el auto-stop a los 30 minutos.
 */
class TailManager(
    val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val active = ConcurrentHashMap<String, Long>()

    fun start(sessionId: String) {
        active[sessionId] = clock() + ttlMillis
    }

    fun stop(sessionId: String) {
        active.remove(sessionId)
    }

    fun isActive(sessionId: String): Boolean {
        val expires = active[sessionId] ?: return false
        if (clock() > expires) {
            active.remove(sessionId)
            return false
        }
        return true
    }

    fun activeSessions(): Set<String> = active.keys.toSet()

    companion object {
        const val DEFAULT_TTL_MILLIS = 30 * 60 * 1000L // 30 minutos
    }
}