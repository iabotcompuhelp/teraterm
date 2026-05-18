package com.opentermx.mcp.operation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OperationScopeValidateTest {

    @Test
    fun `scope vacío permite todo`() {
        val scope = OperationScope()
        val res = scope.validateCommand("anything goes")
        assertEquals(CommandValidation.Allowed, res)
    }

    @Test
    fun `forbidden_commands rechaza substring case-insensitive`() {
        val scope = OperationScope(forbiddenCommands = listOf("reload", "erase"))
        val res = scope.validateCommand("RELOAD now")
        val rejected = assertInstanceOf(CommandValidation.Rejected::class.java, res)
        assertTrue(rejected.reason.contains("reload"))
    }

    @Test
    fun `forbidden_commands no afecta comandos no relacionados`() {
        val scope = OperationScope(forbiddenCommands = listOf("reload"))
        val res = scope.validateCommand("show running-config")
        assertEquals(CommandValidation.Allowed, res)
    }

    @Test
    fun `allowed_commands_prefix exige prefijo cuando la lista no es vacía`() {
        val scope = OperationScope(allowedCommandsPrefix = listOf("show", "configure terminal"))
        assertEquals(CommandValidation.Allowed, scope.validateCommand("show version"))
        assertEquals(CommandValidation.Allowed, scope.validateCommand("configure terminal"))
        val res = scope.validateCommand("router ospf 1")
        assertInstanceOf(CommandValidation.Rejected::class.java, res)
    }

    @Test
    fun `forbidden gana sobre allowed cuando hay match`() {
        val scope = OperationScope(
            allowedCommandsPrefix = listOf("show"),
            forbiddenCommands = listOf("show running-config view"),
        )
        val res = scope.validateCommand("show running-config view secret")
        assertInstanceOf(CommandValidation.Rejected::class.java, res)
        assertTrue((res as CommandValidation.Rejected).reason.contains("forbidden_commands"))
    }

    @Test
    fun `comando vacío o whitespace pasa sin chequeo`() {
        val scope = OperationScope(forbiddenCommands = listOf("x"))
        assertEquals(CommandValidation.Allowed, scope.validateCommand(""))
        assertEquals(CommandValidation.Allowed, scope.validateCommand("   "))
    }
}
