package com.opentermx.mcp.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RateLimiterTest {

    @Test
    fun `burst de 20 sucesivos pasa luego throttle al 21vo`() {
        val limiter = RateLimiter(
            config = RateLimiter.Config(capacityBurst = 20, refillTokensPerSecond = 1.0),
            clock = { 0L },
        )
        repeat(20) { i ->
            val d = limiter.allow("tok")
            assertTrue(d is RateLimiter.Decision.Allow, "request #${i + 1} debería pasar")
        }
        val d = limiter.allow("tok")
        assertTrue(d is RateLimiter.Decision.Throttle, "el 21vo debería ser throttled")
        assertEquals(0, d.remaining)
    }

    @Test
    fun `refill tras un segundo recupera un token`() {
        var now = 0L
        val limiter = RateLimiter(
            config = RateLimiter.Config(capacityBurst = 1, refillTokensPerSecond = 1.0),
            clock = { now },
        )
        assertTrue(limiter.allow("tok") is RateLimiter.Decision.Allow)
        assertTrue(limiter.allow("tok") is RateLimiter.Decision.Throttle)
        now += 1100 // un segundo +
        assertTrue(limiter.allow("tok") is RateLimiter.Decision.Allow)
    }

    @Test
    fun `circuit breaker se abre tras 5 rechazos y libera tras lockout`() {
        var now = 0L
        val limiter = RateLimiter(
            config = RateLimiter.Config(
                capacityBurst = 100,
                refillTokensPerSecond = 100.0,
                circuitThreshold = 5,
                circuitWindowMillis = 60_000,
                circuitLockoutMillis = 10_000,
            ),
            clock = { now },
        )
        // Hasta 4 rechazos: el circuit sigue cerrado.
        repeat(4) {
            limiter.recordRejection("tok", "propose_commands")
            assertTrue(
                limiter.allow("tok", "propose_commands") is RateLimiter.Decision.Allow,
                "circuit no debería estar abierto con solo ${it + 1} rechazos"
            )
        }
        // El 5to abre el circuit.
        limiter.recordRejection("tok", "propose_commands")
        assertTrue(limiter.allow("tok", "propose_commands") is RateLimiter.Decision.CircuitOpen)
        // Otra tool del mismo token no está afectada.
        assertTrue(limiter.allow("tok", "list_sessions") is RateLimiter.Decision.Allow)
        // Tras el lockout vuelve a Allow.
        now += 11_000
        assertTrue(limiter.allow("tok", "propose_commands") is RateLimiter.Decision.Allow)
    }

    @Test
    fun `un approval resetea el contador de rechazos`() {
        val limiter = RateLimiter(
            config = RateLimiter.Config(
                capacityBurst = 100,
                refillTokensPerSecond = 100.0,
                circuitThreshold = 3,
                circuitWindowMillis = 60_000,
                circuitLockoutMillis = 10_000,
            ),
            clock = { 0L },
        )
        limiter.recordRejection("tok", "propose_commands")
        limiter.recordRejection("tok", "propose_commands")
        limiter.recordApproval("tok", "propose_commands")
        limiter.recordRejection("tok", "propose_commands")
        // 2 rechazos pre-approval no cuentan; tenemos solo 1 fresca → circuit cerrado.
        assertTrue(limiter.allow("tok", "propose_commands") is RateLimiter.Decision.Allow)
    }

    @Test
    fun `tokens distintos no se afectan entre sí`() {
        val limiter = RateLimiter(
            config = RateLimiter.Config(capacityBurst = 2, refillTokensPerSecond = 0.0),
            clock = { 0L },
        )
        limiter.allow("alice"); limiter.allow("alice")
        // alice agotó su bucket; bob arranca con bucket lleno.
        assertTrue(limiter.allow("alice") is RateLimiter.Decision.Throttle)
        assertTrue(limiter.allow("bob") is RateLimiter.Decision.Allow)
        assertFalse(false)
    }
}