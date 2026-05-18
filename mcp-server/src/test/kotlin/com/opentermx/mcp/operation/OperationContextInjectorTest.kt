package com.opentermx.mcp.operation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OperationContextInjectorTest {

    private val record = OperationRecord(
        operationId = "op-block-test",
        context = OperationContext(
            operation = OperationMeta(id = "op-block-test", description = "Hacer X"),
            scope = OperationScope(
                devices = listOf("core-router-1"),
                forbiddenCommands = listOf("reload", "erase"),
                allowedCommandsPrefix = listOf("show"),
            ),
            successCriteria = listOf(SuccessCriterion(type = "no_interface_down")),
            constraints = OperationConstraints(requireSnapshot = true),
        ),
        startedAtMillis = 0L,
        initiatedBySessionKey = "test",
    )

    @Test
    fun `renderBlock incluye id, description, devices, forbidden, allowed, criteria y constraints`() {
        val block = OperationContextInjector.renderBlock(record)
        assertTrue(block.startsWith("[OPERATION CONTEXT op-block-test]"))
        assertTrue(block.contains("description: Hacer X"))
        assertTrue(block.contains("scope.devices: core-router-1"))
        assertTrue(block.contains("forbidden_commands: reload, erase"))
        assertTrue(block.contains("allowed_commands_prefix: show"))
        assertTrue(block.contains("success_criteria: 1 pending"))
        assertTrue(block.contains("constraints: require(snapshot)"))
        assertTrue(block.endsWith("---\n"))
    }

    @Test
    fun `injectIntoToolResult prefija el bloque al text del primer content`() {
        val raw = mapOf<String, Any?>(
            "content" to listOf(mapOf("type" to "text", "text" to "ORIGINAL_PAYLOAD")),
            "structuredContent" to mapOf("foo" to "bar"),
            "isError" to false,
        )
        val out = OperationContextInjector.injectIntoToolResult(raw, record)
        @Suppress("UNCHECKED_CAST")
        val content = out["content"] as List<Map<String, Any?>>
        val text = content[0]["text"] as String
        assertTrue(text.startsWith("[OPERATION CONTEXT"))
        assertTrue(text.endsWith("ORIGINAL_PAYLOAD"))
        // structuredContent e isError no se tocan.
        assertEquals(mapOf("foo" to "bar"), out["structuredContent"])
        assertEquals(false, out["isError"])
    }

    @Test
    fun `injectIntoToolResult deja content vacío como estaba`() {
        val raw = mapOf<String, Any?>(
            "content" to emptyList<Map<String, Any?>>(),
            "isError" to false,
        )
        val out = OperationContextInjector.injectIntoToolResult(raw, record)
        assertEquals(raw, out)
    }

    @Test
    fun `injectIntoToolResult ignora content que no es type=text`() {
        val raw = mapOf<String, Any?>(
            "content" to listOf(mapOf("type" to "image", "data" to "...")),
            "isError" to false,
        )
        val out = OperationContextInjector.injectIntoToolResult(raw, record)
        assertEquals(raw, out)
    }

    @Test
    fun `bloque sin scope vacío no agrega líneas en blanco`() {
        val minimalRecord = OperationRecord(
            operationId = "op-minimal",
            context = OperationContext(operation = OperationMeta(id = "op-minimal", description = "x")),
            startedAtMillis = 0L,
            initiatedBySessionKey = "test",
        )
        val block = OperationContextInjector.renderBlock(minimalRecord)
        assertTrue(block.contains("description: x"))
        assertTrue(!block.contains("scope.devices:"))
        assertTrue(!block.contains("forbidden_commands:"))
        assertTrue(!block.contains("constraints:"))
        // Termina en `---` después del header + description.
        assertNotNull(block.lines().last { it.isNotBlank() })
    }
}
