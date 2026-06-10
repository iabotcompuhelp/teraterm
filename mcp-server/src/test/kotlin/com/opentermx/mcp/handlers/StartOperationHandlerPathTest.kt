package com.opentermx.mcp.handlers

import com.opentermx.mcp.operation.InMemoryOperationStore
import com.opentermx.mcp.operation.OperationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Confinamiento de `contextPath`: el path viene del cliente MCP, así que el handler
 * sólo acepta archivos bajo `allowedContextRoot`. Sin esto, un cliente autenticado
 * podía leer cualquier YAML/JSON legible por el proceso (`../../secrets.yaml`).
 */
class StartOperationHandlerPathTest {

    private val validYaml = """
        operation:
          id: "op-path-001"
          description: "Test de confinamiento de contextPath"
        scope:
          devices: ["core-router-1"]
    """.trimIndent()

    private fun handler(root: Path) = StartOperationHandler(
        registry = OperationRegistry(InMemoryOperationStore()),
        allowedContextRoot = root,
    )

    @Test
    fun `contextPath dentro del root permitido carga normal`(@TempDir root: Path) {
        val file = root.resolve("contexts").let {
            Files.createDirectories(it)
            it.resolve("op.yaml")
        }
        Files.writeString(file, validYaml)
        val result = runBlocking {
            handler(root).invoke(mapOf("contextPath" to file.toString()), "session-A")
        }
        assertEquals("op-path-001", result["operationId"])
    }

    @Test
    fun `contextPath fuera del root es rechazado con INVALID_ARGUMENT`(@TempDir root: Path, @TempDir outside: Path) {
        val file = outside.resolve("secreto.yaml")
        Files.writeString(file, validYaml)
        val ex = assertThrows(McpToolException::class.java) {
            runBlocking { handler(root).invoke(mapOf("contextPath" to file.toString()), "session-A") }
        }
        assertEquals(McpToolException.ErrorCode.INVALID_ARGUMENT, ex.code)
        assertTrue(ex.message!!.contains("contextPath"), "mensaje real: ${ex.message}")
    }

    @Test
    fun `traversal relativo desde el root no escapa`(@TempDir root: Path, @TempDir outside: Path) {
        val file = outside.resolve("fuera.yaml")
        Files.writeString(file, validYaml)
        // Path sintácticamente "dentro" del root que normaliza hacia afuera.
        val sneaky = root.toString() + java.io.File.separator + ".." + java.io.File.separator +
            outside.fileName + java.io.File.separator + "fuera.yaml"
        assertThrows(McpToolException::class.java) {
            runBlocking { handler(root).invoke(mapOf("contextPath" to sneaky), "session-A") }
        }
    }

    @Test
    fun `archivo inexistente dentro del root sigue dando error claro de no-existencia`(@TempDir root: Path) {
        val ex = assertThrows(McpToolException::class.java) {
            runBlocking { handler(root).invoke(mapOf("contextPath" to root.resolve("no-esta.yaml").toString()), "session-A") }
        }
        assertEquals(McpToolException.ErrorCode.INVALID_ARGUMENT, ex.code)
    }
}
