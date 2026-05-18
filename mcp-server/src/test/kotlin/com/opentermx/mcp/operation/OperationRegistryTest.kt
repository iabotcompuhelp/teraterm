package com.opentermx.mcp.operation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OperationRegistryTest {

    private fun sampleContext(id: String? = null) = OperationContext(
        operation = OperationMeta(id = id, description = "test op"),
        scope = OperationScope(forbiddenCommands = listOf("reload")),
    )

    @Test
    fun `start asigna id autogenerado cuando el context no trae uno`() {
        val registry = OperationRegistry(InMemoryOperationStore())
        val rec = registry.start("session-A", sampleContext())
        assertTrue(rec.operationId.startsWith("op-"), "id real: ${rec.operationId}")
        assertEquals("test op", rec.context.operation.description)
        assertEquals(rec.operationId, rec.context.operation.id)
    }

    @Test
    fun `start respeta el id del context si viene`() {
        val registry = OperationRegistry(InMemoryOperationStore())
        val rec = registry.start("session-A", sampleContext("op-explicit"))
        assertEquals("op-explicit", rec.operationId)
    }

    @Test
    fun `double start desde la misma sessionKey falla con mensaje claro`() {
        val registry = OperationRegistry(InMemoryOperationStore())
        registry.start("session-A", sampleContext())
        val ex = assertThrows(OperationContextException::class.java) {
            registry.start("session-A", sampleContext())
        }
        assertTrue(ex.message!!.contains("Ya hay una operación activa"))
    }

    @Test
    fun `mismo id en sessionKey distintas también falla`() {
        val registry = OperationRegistry(InMemoryOperationStore())
        registry.start("session-A", sampleContext("op-dup"))
        val ex = assertThrows(OperationContextException::class.java) {
            registry.start("session-B", sampleContext("op-dup"))
        }
        assertTrue(ex.message!!.contains("op-dup"))
    }

    @Test
    fun `forSessionKey devuelve la op activa y null tras end`() {
        val registry = OperationRegistry(InMemoryOperationStore())
        val rec = registry.start("session-A", sampleContext())
        assertNotNull(registry.forSessionKey("session-A"))
        registry.end("session-A", rec.operationId)
        assertNull(registry.forSessionKey("session-A"))
    }

    @Test
    fun `end de otra sessionKey es rechazado`() {
        val registry = OperationRegistry(InMemoryOperationStore())
        val rec = registry.start("session-A", sampleContext())
        val ex = assertThrows(OperationContextException::class.java) {
            registry.end("session-B", rec.operationId)
        }
        assertTrue(ex.message!!.contains("no pertenece"))
    }

    @Test
    fun `end de id inexistente es rechazado`() {
        val registry = OperationRegistry(InMemoryOperationStore())
        val ex = assertThrows(OperationContextException::class.java) {
            registry.end("session-A", "op-fantasma")
        }
        assertTrue(ex.message!!.contains("no encontrada"))
    }

    @Test
    fun `recovery del store reabre operations en byOperationId pero no en sessionKey`() {
        // Simulamos restart: un store que tiene una op abierta.
        val store = InMemoryOperationStore()
        OperationRegistry(store).start("session-A", sampleContext("op-resurrected"))
        // Nuevo registry sobre el mismo store: lookup por id funciona.
        val resumed = OperationRegistry(store)
        assertNotNull(resumed.forOperationId("op-resurrected"))
        // Pero la sessionKey original NO está bindeada (no hacemos rebinding implícito).
        assertNull(resumed.forSessionKey("session-A"))
    }
}
