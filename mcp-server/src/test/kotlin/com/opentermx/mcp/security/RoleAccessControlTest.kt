package com.opentermx.mcp.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoleAccessControlTest {

    @Test
    fun `OPERATOR puede invocar tools de descubrimiento y mutación`() {
        assertTrue(RoleAccessControl.allows(Role.OPERATOR, "list_sessions"))
        assertTrue(RoleAccessControl.allows(Role.OPERATOR, "inventory_list"))
        assertTrue(RoleAccessControl.allows(Role.OPERATOR, "propose_commands"))
        assertTrue(RoleAccessControl.allows(Role.OPERATOR, "open_session"))
        assertTrue(RoleAccessControl.allows(Role.OPERATOR, "start_operation"))
    }

    @Test
    fun `OPERATOR NO puede invocar compliance_evaluate`() {
        assertFalse(RoleAccessControl.allows(Role.OPERATOR, "compliance_evaluate"))
    }

    @Test
    fun `COMPLIANCE puede invocar compliance_evaluate y lectura, NO ejecución`() {
        assertTrue(RoleAccessControl.allows(Role.COMPLIANCE, "compliance_evaluate"))
        assertTrue(RoleAccessControl.allows(Role.COMPLIANCE, "inspect_session"))
        assertTrue(RoleAccessControl.allows(Role.COMPLIANCE, "inventory_describe"))
        assertFalse(RoleAccessControl.allows(Role.COMPLIANCE, "propose_commands"))
        assertFalse(RoleAccessControl.allows(Role.COMPLIANCE, "open_session"))
        assertFalse(RoleAccessControl.allows(Role.COMPLIANCE, "run_macro"))
        assertFalse(RoleAccessControl.allows(Role.COMPLIANCE, "start_operation"))
    }

    @Test
    fun `VALIDATOR es read-only`() {
        assertTrue(RoleAccessControl.allows(Role.VALIDATOR, "inspect_session"))
        assertTrue(RoleAccessControl.allows(Role.VALIDATOR, "inventory_list"))
        assertFalse(RoleAccessControl.allows(Role.VALIDATOR, "propose_commands"))
        assertFalse(RoleAccessControl.allows(Role.VALIDATOR, "open_session"))
        assertFalse(RoleAccessControl.allows(Role.VALIDATOR, "compliance_evaluate"))
    }

    @Test
    fun `tool desconocida rechazada para todos los roles`() {
        for (role in Role.entries) {
            assertFalse(RoleAccessControl.allows(role, "no_such_tool"))
        }
    }

    @Test
    fun `Role fromHeader default OPERATOR cuando header falta o es desconocido`() {
        assertEquals(Role.OPERATOR, Role.fromHeader(null))
        assertEquals(Role.OPERATOR, Role.fromHeader(""))
        assertEquals(Role.OPERATOR, Role.fromHeader("   "))
        assertEquals(Role.OPERATOR, Role.fromHeader("hacker"))
    }

    @Test
    fun `Role fromHeader es case-insensitive`() {
        assertEquals(Role.COMPLIANCE, Role.fromHeader("compliance"))
        assertEquals(Role.COMPLIANCE, Role.fromHeader("COMPLIANCE"))
        assertEquals(Role.COMPLIANCE, Role.fromHeader("  Compliance  "))
        assertEquals(Role.VALIDATOR, Role.fromHeader("validator"))
        assertEquals(Role.OPERATOR, Role.fromHeader("operator"))
    }
}
