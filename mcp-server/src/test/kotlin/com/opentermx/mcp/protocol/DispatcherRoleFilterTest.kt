package com.opentermx.mcp.protocol

import com.opentermx.mcp.handlers.ToolHandler
import com.opentermx.mcp.security.Role
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Phase 3 Fase 3: verifica que McpDispatcher filtra `tools/call` por rol según
 * [com.opentermx.mcp.security.RoleAccessControl]. Cualquier tool fuera del scope del rol
 * recibe error -32601, independientemente de que la tool exista o esté registrada.
 */
class DispatcherRoleFilterTest {

    private class EchoHandler(override val definition: ToolDef) : ToolHandler {
        override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> =
            linkedMapOf("ok" to true)
    }

    private fun dispatcher() = McpDispatcher(
        handlers = listOf(
            EchoHandler(ToolDefinitions.LIST_SESSIONS),
            EchoHandler(ToolDefinitions.PROPOSE_COMMANDS),
            EchoHandler(ToolDefinitions.COMPLIANCE_EVALUATE),
        ),
    )

    private fun transport(role: Role) = TransportContext(
        sessionKey = "test", protocolVersionHeader = "2024-11-05",
        enforceVersionHeader = false, role = role,
    )

    @Test
    fun `OPERATOR puede llamar propose_commands pero NO compliance_evaluate`() {
        val d = dispatcher()
        val ok = d.handle(
            JsonRpcRequest(id = 1, method = "tools/call",
                params = mapOf("name" to "propose_commands", "arguments" to mapOf("sessionId" to "x", "commands" to listOf("show version")))),
            transport(Role.OPERATOR),
        )!!
        assertNull(ok.error)

        val blocked = d.handle(
            JsonRpcRequest(id = 2, method = "tools/call",
                params = mapOf("name" to "compliance_evaluate", "arguments" to emptyMap<String, Any?>())),
            transport(Role.OPERATOR),
        )!!
        assertNotNull(blocked.error)
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, blocked.error!!.code)
    }

    @Test
    fun `COMPLIANCE puede llamar compliance_evaluate pero NO propose_commands`() {
        val d = dispatcher()
        val blocked = d.handle(
            JsonRpcRequest(id = 1, method = "tools/call",
                params = mapOf("name" to "propose_commands", "arguments" to mapOf("sessionId" to "x", "commands" to listOf("show version")))),
            transport(Role.COMPLIANCE),
        )!!
        assertNotNull(blocked.error)
        assertEquals(JsonRpcError.METHOD_NOT_FOUND, blocked.error!!.code)

        // compliance_evaluate sí está permitido — el handler echo devolverá ok.
        val ok = d.handle(
            JsonRpcRequest(id = 2, method = "tools/call",
                params = mapOf("name" to "compliance_evaluate", "arguments" to emptyMap<String, Any?>())),
            transport(Role.COMPLIANCE),
        )!!
        assertNull(ok.error)
    }

    @Test
    fun `VALIDATOR no puede ejecutar nada mutativo`() {
        val d = dispatcher()
        for (tool in listOf("propose_commands", "compliance_evaluate")) {
            val r = d.handle(
                JsonRpcRequest(id = 1, method = "tools/call",
                    params = mapOf("name" to tool, "arguments" to emptyMap<String, Any?>())),
                transport(Role.VALIDATOR),
            )!!
            assertNotNull(r.error, "$tool debería rechazarse para VALIDATOR")
        }
    }

    @Test
    fun `clientes pre-Fase 3 sin header son tratados como OPERATOR`() {
        // Construimos TransportContext default sin pasar role explícito — debe ser OPERATOR.
        val ctx = TransportContext("test", "2024-11-05", false)
        assertEquals(Role.OPERATOR, ctx.role)
    }
}
