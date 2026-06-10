package com.opentermx.mcp.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * El auto-stop de `tail_session` que la tool promete ("Auto-stop a los 30 minutos")
 * se enforcea acá: [TailManager.isActive] expira lazy contra el clock inyectado y el
 * subscriber del EventBus en McpServer corta el stream apenas isActive devuelve false.
 * Hasta este test la promesa estaba sólo en la doc.
 */
class TailManagerTest {

    private var now = 1_000_000L
    private val ttl = 1_000L
    private val manager = TailManager(ttlMillis = ttl, clock = { now })

    @Test
    fun `el TTL default es los 30 minutos que promete la doc de la tool`() {
        assertEquals(30 * 60 * 1_000L, TailManager.DEFAULT_TTL_MILLIS)
        assertEquals(TailManager.DEFAULT_TTL_MILLIS, TailManager(clock = { now }).ttlMillis)
    }

    @Test
    fun `activo dentro del TTL, auto-stop una vez vencido`() {
        manager.start("s1")
        assertTrue(manager.isActive("s1"))

        now += ttl // borde: clock() > expires es estricto, en expires exacto sigue activo
        assertTrue(manager.isActive("s1"), "en el instante exacto de expiry sigue activo")

        now += 1
        assertFalse(manager.isActive("s1"), "un milisegundo después del TTL debe auto-stopear")
    }

    @Test
    fun `isActive expirado limpia la entrada del set de activas`() {
        manager.start("s1")
        now += ttl + 1
        assertFalse(manager.isActive("s1"))
        assertTrue(manager.activeSessions().isEmpty(), "la entrada expirada debe removerse, no quedar zombie")
    }

    @Test
    fun `stop explicito desactiva sin esperar el TTL`() {
        manager.start("s1")
        manager.stop("s1")
        assertFalse(manager.isActive("s1"))
    }

    @Test
    fun `re-start renueva el expiry`() {
        manager.start("s1")
        now += ttl - 100
        manager.start("s1") // renueva: nuevo expiry = now + ttl
        now += 200          // ya pasó el expiry original, no el renovado
        assertTrue(manager.isActive("s1"), "el segundo start debe renovar la ventana completa")
        now += ttl
        assertFalse(manager.isActive("s1"))
    }

    @Test
    fun `cada sesion expira de forma independiente`() {
        manager.start("vieja")
        now += 600
        manager.start("nueva")
        now += ttl - 600 + 1 // "vieja" vencida, "nueva" todavía no
        assertFalse(manager.isActive("vieja"))
        assertTrue(manager.isActive("nueva"))
        assertEquals(setOf("nueva"), manager.activeSessions())
    }

    @Test
    fun `sesion nunca arrancada no esta activa`() {
        assertFalse(manager.isActive("fantasma"))
    }
}
