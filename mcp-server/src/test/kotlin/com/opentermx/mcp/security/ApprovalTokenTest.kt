package com.opentermx.mcp.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApprovalTokenTest {

    private val secret = ByteArray(32) { (it + 1).toByte() }
    private val otherSecret = ByteArray(32) { (it + 50).toByte() }

    @Test
    fun `issue + verify con mismos commands devuelve Valid`() {
        val token = ApprovalTokens.issue(
            operationId = "op-1", deviceAlias = "core-router-1",
            commands = listOf("show version", "configure terminal"), secret = secret,
        )
        val result = ApprovalTokens.verify(
            token = token, expectedOperationId = "op-1",
            expectedDeviceAlias = "core-router-1",
            commands = listOf("show version", "configure terminal"),
            secret = secret,
        )
        assertInstanceOf(Verification.Valid::class.java, result)
        val valid = result as Verification.Valid
        assertEquals("op-1", valid.payload.operationId)
        assertEquals(Role.COMPLIANCE.name, valid.payload.role)
    }

    @Test
    fun `commands distintos invalidan el token`() {
        val token = ApprovalTokens.issue("op-1", "d1", listOf("a", "b"), secret)
        val result = ApprovalTokens.verify(
            token = token, expectedOperationId = "op-1", expectedDeviceAlias = "d1",
            commands = listOf("a", "c"), secret = secret,
        )
        val invalid = assertInstanceOf(Verification.Invalid::class.java, result)
        assertTrue(invalid.reason.contains("no matchean"), "msg: ${invalid.reason}")
    }

    @Test
    fun `secret distinto invalida el token (firma no matchea)`() {
        val token = ApprovalTokens.issue("op-1", null, listOf("a"), secret)
        val result = ApprovalTokens.verify(
            token = token, expectedOperationId = "op-1", expectedDeviceAlias = null,
            commands = listOf("a"), secret = otherSecret,
        )
        val invalid = assertInstanceOf(Verification.Invalid::class.java, result)
        assertTrue(invalid.reason.contains("HMAC"), "msg: ${invalid.reason}")
    }

    @Test
    fun `operationId distinto invalida el token`() {
        val token = ApprovalTokens.issue("op-1", null, listOf("a"), secret)
        val result = ApprovalTokens.verify(
            token = token, expectedOperationId = "op-2", expectedDeviceAlias = null,
            commands = listOf("a"), secret = secret,
        )
        val invalid = assertInstanceOf(Verification.Invalid::class.java, result)
        assertTrue(invalid.reason.contains("operationId"), "msg: ${invalid.reason}")
    }

    @Test
    fun `token de otro device es rechazado cuando el token declara device`() {
        val token = ApprovalTokens.issue("op-1", "device-A", listOf("a"), secret)
        val result = ApprovalTokens.verify(
            token = token, expectedOperationId = "op-1",
            expectedDeviceAlias = "device-B",
            commands = listOf("a"), secret = secret,
        )
        val invalid = assertInstanceOf(Verification.Invalid::class.java, result)
        assertTrue(invalid.reason.contains("deviceAlias"), "msg: ${invalid.reason}")
    }

    @Test
    fun `token sin device sirve para cualquier device`() {
        val token = ApprovalTokens.issue("op-1", null, listOf("a"), secret)
        val result = ApprovalTokens.verify(
            token = token, expectedOperationId = "op-1",
            expectedDeviceAlias = "cualquiera",
            commands = listOf("a"), secret = secret,
        )
        assertInstanceOf(Verification.Valid::class.java, result)
    }

    @Test
    fun `token expirado es rechazado`() {
        val token = ApprovalTokens.issue(
            "op-1", null, listOf("a"), secret,
            nowMillis = 1_000_000L, ttlMillis = 1_000L,
        )
        val result = ApprovalTokens.verify(
            token = token, expectedOperationId = "op-1", expectedDeviceAlias = null,
            commands = listOf("a"), secret = secret,
            nowMillis = 2_000_000L,
        )
        val invalid = assertInstanceOf(Verification.Invalid::class.java, result)
        assertTrue(invalid.reason.contains("expirado"), "msg: ${invalid.reason}")
    }

    @Test
    fun `formato corrupto es rechazado con mensaje claro`() {
        val invalid = ApprovalTokens.verify(
            "garbage", "op-1", null, listOf("a"), secret,
        )
        val res = assertInstanceOf(Verification.Invalid::class.java, invalid)
        assertTrue(res.reason.contains("formato inválido"), "msg: ${res.reason}")
    }
}
