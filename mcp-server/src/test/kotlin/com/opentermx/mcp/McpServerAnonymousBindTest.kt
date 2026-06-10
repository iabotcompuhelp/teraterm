package com.opentermx.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket

/**
 * Invariante de seguridad: un McpServer SIN autenticación (token null/blank y sin
 * tokenVerifier) sólo puede bindear a loopback. Antes de este fix, `token = null`
 * deshabilitaba el auth check por completo — bindear a `0.0.0.0` sin token dejaba
 * el server abierto a la red sin que nada lo impidiera.
 */
class McpServerAnonymousBindTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `anonimo en bind no-loopback falla con IllegalStateException sin arrancar`() {
        val server = McpServer(handlers = emptyList())
        val ex = assertThrows(IllegalStateException::class.java) {
            server.start(port = freePort(), bindAddress = "0.0.0.0", token = null)
        }
        assertTrue(ex.message!!.contains("loopback"), "el mensaje debe explicar el porqué: ${ex.message}")
        assertEquals(McpServer.Status.FAILED, server.status.value, "el rechazo debe ser observable en la status bar")
        assertTrue(server.lastError()!!.contains("loopback"), "lastError alimenta el tooltip de la UI")
    }

    @Test
    fun `anonimo con token blank tampoco pasa — blank no es auth`() {
        val server = McpServer(handlers = emptyList())
        assertThrows(IllegalStateException::class.java) {
            server.start(port = freePort(), bindAddress = "192.168.1.10", token = "  ")
        }
    }

    @Test
    fun `anonimo en loopback sigue siendo legal`() {
        val server = McpServer(handlers = emptyList())
        val port = freePort()
        try {
            server.start(port = port, bindAddress = "127.0.0.1", token = null)
            assertEquals(McpServer.Status.RUNNING, server.status.value)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `verifier custom habilita bind no-loopback sin token legacy`() {
        val server = McpServer(handlers = emptyList(), tokenVerifier = { it == "secreto" })
        val port = freePort()
        try {
            server.start(port = port, bindAddress = "0.0.0.0", token = null)
            assertEquals(McpServer.Status.RUNNING, server.status.value)
        } finally {
            server.stop()
        }
    }
}
