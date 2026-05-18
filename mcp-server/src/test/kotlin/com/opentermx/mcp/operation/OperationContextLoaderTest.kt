package com.opentermx.mcp.operation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class OperationContextLoaderTest {

    private val validYaml = """
        operation:
          id: "op-test-001"
          description: "Activar OSPF area 0"
          initiated_by: "operator@example.com"
        scope:
          devices: ["core-router-1"]
          allowed_commands_prefix: ["show", "configure terminal", "router ospf"]
          forbidden_commands: ["reload", "erase"]
        success_criteria:
          - type: "command_output_contains"
            command: "show ip ospf neighbor"
            pattern: "FULL"
        constraints:
          max_duration_minutes: 15
          require_compliance_approval: true
          require_snapshot: true
    """.trimIndent()

    @Test
    fun `yaml válido carga con todos los campos`() {
        val ctx = OperationContextLoader.fromYamlString(validYaml)
        assertEquals("op-test-001", ctx.operation.id)
        assertEquals("Activar OSPF area 0", ctx.operation.description)
        assertEquals(listOf("core-router-1"), ctx.scope.devices)
        assertEquals(listOf("reload", "erase"), ctx.scope.forbiddenCommands)
        assertEquals(1, ctx.successCriteria.size)
        assertEquals("FULL", ctx.successCriteria[0].pattern)
        assertTrue(ctx.constraints.requireComplianceApproval)
        assertTrue(ctx.constraints.requireSnapshot)
        assertEquals(15, ctx.constraints.maxDurationMinutes)
    }

    @Test
    fun `falta description dispara error de schema con detalle`() {
        val bad = """
            operation:
              id: "op-x"
            scope: {}
        """.trimIndent()
        val ex = assertThrows(OperationContextException::class.java) {
            OperationContextLoader.fromYamlString(bad)
        }
        assertTrue(ex.message!!.contains("description"), "mensaje real: ${ex.message}")
    }

    @Test
    fun `id con caracteres prohibidos dispara error de schema`() {
        val bad = """
            operation:
              id: "op con espacios"
              description: "x"
            scope: {}
        """.trimIndent()
        val ex = assertThrows(OperationContextException::class.java) {
            OperationContextLoader.fromYamlString(bad)
        }
        assertTrue(ex.message!!.contains("pattern", ignoreCase = true), "mensaje real: ${ex.message}")
    }

    @Test
    fun `success_criteria con type desconocido es rechazado`() {
        val bad = """
            operation:
              description: "x"
            scope: {}
            success_criteria:
              - type: "i_made_this_up"
        """.trimIndent()
        val ex = assertThrows(OperationContextException::class.java) {
            OperationContextLoader.fromYamlString(bad)
        }
        assertTrue(ex.message!!.contains("i_made_this_up") || ex.message!!.contains("enum"),
            "mensaje real: ${ex.message}")
    }

    @Test
    fun `inline map carga con shape equivalente al YAML`() {
        val inline: Map<String, Any?> = mapOf(
            "operation" to mapOf("description" to "test inline"),
            "scope" to mapOf("forbidden_commands" to listOf("reload")),
        )
        val ctx = OperationContextLoader.fromInline(inline)
        assertEquals("test inline", ctx.operation.description)
        assertEquals(listOf("reload"), ctx.scope.forbiddenCommands)
    }

    @Test
    fun `fromPath leyendo desde disco funciona`() {
        val tmp = Files.createTempFile("opctx", ".yaml")
        tmp.toFile().writeText(validYaml)
        try {
            val ctx = OperationContextLoader.fromPath(tmp)
            assertEquals("op-test-001", ctx.operation.id)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `fromPath con archivo inexistente lanza error claro`() {
        val ex = assertThrows(OperationContextException::class.java) {
            OperationContextLoader.fromPath(java.nio.file.Path.of("/no/existe/op.yaml"))
        }
        assertTrue(ex.message!!.contains("No existe"))
    }
}
