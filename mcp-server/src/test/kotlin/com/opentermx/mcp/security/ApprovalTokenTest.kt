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

    @Test
    fun `token con tres partes es rechazado`() {
        val token = ApprovalTokens.issue("op-1", null, listOf("a"), secret)
        val res = ApprovalTokens.verify("$token.extra", "op-1", null, listOf("a"), secret)
        val invalid = assertInstanceOf(Verification.Invalid::class.java, res)
        assertTrue(invalid.reason.contains("formato inválido"), "msg: ${invalid.reason}")
    }

    // ------------------------------------------------------------- tampering

    /**
     * El ataque que la firma tiene que parar: tomar un token legítimo, extender el `exp`
     * del payload y reusar la firma original. Distinto del test de "secret distinto" —
     * acá el secret es el correcto, lo adulterado es el payload.
     */
    @Test
    fun `payload adulterado con la firma original es rechazado por HMAC`() {
        val token = ApprovalTokens.issue(
            "op-1", null, listOf("a"), secret,
            nowMillis = 1_000_000L, ttlMillis = 1_000L,
        )
        val (payloadB64, sigB64) = token.split('.').let { it[0] to it[1] }
        val b64 = java.util.Base64.getUrlDecoder()
        val payloadJson = String(b64.decode(payloadB64), Charsets.UTF_8)
        // Extender exp un año: de 1_001_000 a 32_000_000_000.
        val forgedJson = payloadJson.replace(Regex("\"exp\":\\d+"), "\"exp\":32000000000")
        assertTrue(forgedJson != payloadJson, "el replace del exp tiene que haber tocado el payload")
        val forgedB64 = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(forgedJson.toByteArray(Charsets.UTF_8))

        val res = ApprovalTokens.verify(
            "$forgedB64.$sigB64", "op-1", null, listOf("a"), secret,
            nowMillis = 2_000_000L, // ya expirado para el payload original
        )
        val invalid = assertInstanceOf(Verification.Invalid::class.java, res)
        assertTrue(invalid.reason.contains("HMAC"), "msg: ${invalid.reason}")
    }

    @Test
    fun `bit flip en la firma es rechazado por HMAC`() {
        val token = ApprovalTokens.issue("op-1", null, listOf("a"), secret)
        val (payloadB64, sigB64) = token.split('.').let { it[0] to it[1] }
        // Cambiar un carácter de la firma por otro del alfabeto base64url (sigue decodificando).
        val flipped = (if (sigB64[0] == 'A') 'B' else 'A') + sigB64.substring(1)
        val res = ApprovalTokens.verify("$payloadB64.$flipped", "op-1", null, listOf("a"), secret)
        val invalid = assertInstanceOf(Verification.Invalid::class.java, res)
        assertTrue(invalid.reason.contains("HMAC"), "msg: ${invalid.reason}")
    }

    // ------------------------------------------------------- bordes de expiry

    @Test
    fun `el limite de expiry es inclusivo - valido exactamente en exp, invalido un milisegundo despues`() {
        val token = ApprovalTokens.issue(
            "op-1", null, listOf("a"), secret,
            nowMillis = 1_000_000L, ttlMillis = 1_000L, // exp = 1_001_000
        )
        val atExp = ApprovalTokens.verify(
            token, "op-1", null, listOf("a"), secret, nowMillis = 1_001_000L,
        )
        assertInstanceOf(Verification.Valid::class.java, atExp, "en exp exacto el check `exp < now` no dispara")

        val pastExp = ApprovalTokens.verify(
            token, "op-1", null, listOf("a"), secret, nowMillis = 1_001_001L,
        )
        assertInstanceOf(Verification.Invalid::class.java, pastExp)
    }

    // ---------------------------------------------------------- clamping TTL

    @Test
    fun `ttl mayor a 60 minutos se clampea al maximo`() {
        val now = 1_000_000L
        val oneHour = 60 * 60 * 1_000L
        val token = ApprovalTokens.issue(
            "op-1", null, listOf("a"), secret,
            nowMillis = now, ttlMillis = 24 * oneHour, // pide 24h
        )
        val beforeMax = ApprovalTokens.verify(
            token, "op-1", null, listOf("a"), secret, nowMillis = now + oneHour,
        )
        assertInstanceOf(Verification.Valid::class.java, beforeMax, "dentro de la hora sigue válido")

        val afterMax = ApprovalTokens.verify(
            token, "op-1", null, listOf("a"), secret, nowMillis = now + oneHour + 1,
        )
        val invalid = assertInstanceOf(Verification.Invalid::class.java, afterMax)
        assertTrue(invalid.reason.contains("expirado"), "el TTL de 24h tenía que clampearse a 1h: ${invalid.reason}")
    }

    @Test
    fun `ttl no positivo se clampea a 1 segundo`() {
        val now = 1_000_000L
        val token = ApprovalTokens.issue(
            "op-1", null, listOf("a"), secret, nowMillis = now, ttlMillis = 0L,
        )
        assertInstanceOf(
            Verification.Valid::class.java,
            ApprovalTokens.verify(token, "op-1", null, listOf("a"), secret, nowMillis = now + 1_000L),
        )
        assertInstanceOf(
            Verification.Invalid::class.java,
            ApprovalTokens.verify(token, "op-1", null, listOf("a"), secret, nowMillis = now + 1_001L),
        )
    }

    @Test
    fun `no se firma una lista vacia de comandos`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            ApprovalTokens.issue("op-1", null, emptyList(), secret)
        }
    }
}
