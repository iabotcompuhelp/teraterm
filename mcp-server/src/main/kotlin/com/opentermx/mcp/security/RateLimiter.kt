package com.opentermx.mcp.security

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Rate limiter por token (key = IP + bearer-prefix) con dos mecanismos combinados:
 *
 *  1. **Token bucket** para throttle general — limita la tasa sostenida de requests para
 *     evitar que un cliente saturado o un cliente IA bugueado tire el servidor. Defaults
 *     conservadores: 60 req/min sostenido con burst de 20 (un humano interactivo nunca
 *     llega a esos números; un script de IA en loop sí).
 *
 *  2. **Circuit breaker por tool** — si una tool específica acumula muchas decisiones
 *     "rechazadas" del operador (ej: `propose_commands` con `approved:false`), el LLM
 *     cliente está pateando contra una invariante de seguridad humana y deberíamos
 *     forzarlo a pausar. Tras 5 rechazos consecutivos en 5 minutos, la tool queda
 *     bloqueada para ese token durante 10 minutos.
 *
 * Thread-safe: todas las operaciones internas usan estructuras concurrentes; ningún lock
 * global. Stateless por fuera (todo el estado vive en el [RateLimiter]).
 */
class RateLimiter(
    private val config: Config = Config(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Config(
        val capacityBurst: Int = 20,
        val refillTokensPerSecond: Double = 1.0, // 60/min
        val circuitWindowMillis: Long = 5 * 60 * 1000L,
        val circuitThreshold: Int = 5,
        val circuitLockoutMillis: Long = 10 * 60 * 1000L,
    )

    private val buckets = ConcurrentHashMap<String, TokenBucket>()
    private val circuits = ConcurrentHashMap<CircuitKey, CircuitState>()

    sealed interface Decision {
        val remaining: Int
        val resetSeconds: Long

        data class Allow(override val remaining: Int, override val resetSeconds: Long) : Decision
        data class Throttle(override val remaining: Int, override val resetSeconds: Long, val reason: String) : Decision
        data class CircuitOpen(override val remaining: Int, override val resetSeconds: Long, val tool: String) : Decision
    }

    /**
     * Decide si admitir el request. Si el bucket no tiene tokens, devuelve `Throttle` con
     * la cantidad de segundos hasta el próximo refill. Si la tool en cuestión tiene el
     * circuit-breaker abierto, devuelve `CircuitOpen` aunque tenga tokens.
     */
    fun allow(tokenKey: String, tool: String? = null): Decision {
        val bucket = buckets.computeIfAbsent(tokenKey) { TokenBucket(config.capacityBurst.toDouble(), clock()) }
        val remaining = bucket.tryConsume(now = clock(), refillPerMs = config.refillTokensPerSecond / 1000.0,
            capacity = config.capacityBurst.toDouble())
        if (remaining < 0) {
            val resetSec = ((1.0 / config.refillTokensPerSecond) + 0.5).toLong()
            return Decision.Throttle(remaining = 0, resetSeconds = resetSec,
                reason = "Bucket vacío: máx ${config.capacityBurst} burst y ${config.refillTokensPerSecond * 60} req/min sostenido")
        }
        if (tool != null) {
            val circuit = circuits[CircuitKey(tokenKey, tool)]
            if (circuit != null && circuit.lockedUntilMillis > clock()) {
                val resetSec = ((circuit.lockedUntilMillis - clock()) / 1000L).coerceAtLeast(1L)
                return Decision.CircuitOpen(remaining = remaining.toInt(), resetSeconds = resetSec, tool = tool)
            }
        }
        return Decision.Allow(remaining = remaining.toInt(), resetSeconds = 0L)
    }

    /**
     * Notifica al limiter que un request a [tool] fue rechazado por el operador (relevante
     * para `propose_commands`). Si supera el threshold, abre el circuit para ese token+tool.
     */
    fun recordRejection(tokenKey: String, tool: String) {
        val key = CircuitKey(tokenKey, tool)
        val now = clock()
        circuits.compute(key) { _, current ->
            val recent = (current?.recentRejections.orEmpty()).filter { now - it < config.circuitWindowMillis } + now
            if (recent.size >= config.circuitThreshold) {
                CircuitState(lockedUntilMillis = now + config.circuitLockoutMillis, recentRejections = emptyList())
            } else {
                CircuitState(lockedUntilMillis = current?.lockedUntilMillis ?: 0L, recentRejections = recent)
            }
        }
    }

    /** Resetea el counter de rejections para [tool] (ej: tras una aprobación exitosa). */
    fun recordApproval(tokenKey: String, tool: String) {
        val key = CircuitKey(tokenKey, tool)
        circuits.computeIfPresent(key) { _, current ->
            current.copy(recentRejections = emptyList())
        }
    }

    private data class CircuitKey(val tokenKey: String, val tool: String)
    private data class CircuitState(val lockedUntilMillis: Long, val recentRejections: List<Long>)

    private class TokenBucket(
        @Volatile var tokens: Double,
        @Volatile var lastRefillMillis: Long,
    ) {
        @Synchronized
        fun tryConsume(now: Long, refillPerMs: Double, capacity: Double): Double {
            val elapsed = (now - lastRefillMillis).coerceAtLeast(0L)
            tokens = (tokens + elapsed * refillPerMs).coerceAtMost(capacity)
            lastRefillMillis = now
            return if (tokens >= 1.0) {
                tokens -= 1.0
                tokens
            } else {
                -1.0
            }
        }
    }
}