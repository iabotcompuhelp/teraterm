package com.opentermx.mcp.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Phase 3 Fase 3 — Approval token.
 *
 * Token opaco firmado HMAC-SHA256 que un agente con rol COMPLIANCE emite tras evaluar una
 * propuesta de comandos. El handler de `propose_commands` lo verifica cuando la operación
 * activa tiene `constraints.require_compliance_approval: true`.
 *
 * Formato wire:
 *   `base64url(json(payload)) + "." + base64url(hmac)`
 *
 * El token NUNCA se persiste — el ciclo es:
 *   1. compliance LLM llama `compliance_evaluate` → server firma y devuelve plaintext.
 *   2. compliance LLM lo pasa al operator LLM (out-of-band, fuera del MCP server).
 *   3. operator LLM lo manda en `args["approvalToken"]` de `propose_commands`.
 *   4. server verifica firma, payload, expiry, scope — descarta.
 */
object ApprovalTokens {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    /** Default 15 min. Configurable por el caller; nunca > 60 min. */
    const val DEFAULT_TTL_MILLIS: Long = 15 * 60 * 1_000L
    private const val MAX_TTL_MILLIS: Long = 60 * 60 * 1_000L

    /**
     * Firma un payload y devuelve el token wire-ready.
     */
    fun issue(
        operationId: String,
        deviceAlias: String?,
        commands: List<String>,
        secret: ByteArray,
        nowMillis: Long = System.currentTimeMillis(),
        ttlMillis: Long = DEFAULT_TTL_MILLIS,
    ): String {
        require(operationId.isNotBlank()) { "operationId requerido" }
        require(commands.isNotEmpty()) { "no se firma una lista vacía de comandos" }
        val effectiveTtl = ttlMillis.coerceAtMost(MAX_TTL_MILLIS).coerceAtLeast(1_000L)
        val payload = ApprovalPayload(
            operationId = operationId,
            role = Role.COMPLIANCE.name,
            deviceAlias = deviceAlias,
            commandsHash = sha256Hex(commands.joinToString("\n")),
            exp = nowMillis + effectiveTtl,
            nonce = UUID.randomUUID().toString(),
        )
        val payloadJson = mapper.writeValueAsBytes(payload)
        val mac = hmac(secret, payloadJson)
        val b64 = Base64.getUrlEncoder().withoutPadding()
        return b64.encodeToString(payloadJson) + "." + b64.encodeToString(mac)
    }

    /**
     * Verifica un token: estructura, HMAC, expiry, scope contra commands+operationId+device.
     * Devuelve sealed result; el caller convierte [Verification.Invalid.reason] en mensaje
     * accionable hacia el cliente.
     */
    fun verify(
        token: String,
        expectedOperationId: String,
        expectedDeviceAlias: String?,
        commands: List<String>,
        secret: ByteArray,
        nowMillis: Long = System.currentTimeMillis(),
    ): Verification {
        val parts = token.split('.')
        if (parts.size != 2) return Verification.Invalid("formato inválido del approval token (esperado: payload.hmac)")

        val b64 = Base64.getUrlDecoder()
        val payloadBytes = runCatching { b64.decode(parts[0]) }.getOrNull()
            ?: return Verification.Invalid("payload del token no es base64url válido")
        val sigBytes = runCatching { b64.decode(parts[1]) }.getOrNull()
            ?: return Verification.Invalid("firma del token no es base64url válida")

        val expected = hmac(secret, payloadBytes)
        if (!MessageDigest.isEqual(expected, sigBytes)) {
            return Verification.Invalid("firma HMAC del approval token no matchea (revocado o tampering)")
        }

        val payload = runCatching { mapper.readValue<ApprovalPayload>(payloadBytes) }.getOrNull()
            ?: return Verification.Invalid("payload del token no es JSON válido")

        if (payload.operationId != expectedOperationId) {
            return Verification.Invalid(
                "operationId del token (${payload.operationId}) no matchea la operation activa ($expectedOperationId)"
            )
        }
        if (!matchesDevice(payload.deviceAlias, expectedDeviceAlias)) {
            return Verification.Invalid(
                "deviceAlias del token (${payload.deviceAlias}) no matchea el target ($expectedDeviceAlias)"
            )
        }
        if (payload.exp < nowMillis) {
            return Verification.Invalid("approval token expirado (exp=${payload.exp}, ahora=$nowMillis)")
        }
        val actualHash = sha256Hex(commands.joinToString("\n"))
        if (payload.commandsHash != actualHash) {
            return Verification.Invalid(
                "los comandos a ejecutar no matchean lo que compliance firmó " +
                    "(hash esperado=${payload.commandsHash.take(12)}…, actual=${actualHash.take(12)}…)"
            )
        }

        return Verification.Valid(payload)
    }

    /**
     * Política de matching device:
     *  - Si el token NO declara device, sirve para cualquier device.
     *  - Si el token declara device, debe ser exactamente el target.
     */
    private fun matchesDevice(tokenDevice: String?, target: String?): Boolean {
        if (tokenDevice.isNullOrBlank()) return true
        return tokenDevice == target
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Payload firmado. Se serializa con orden de campos estable (Jackson respeta el orden de
 * declaración de Kotlin), así que la firma es determinística para los mismos valores.
 */
data class ApprovalPayload(
    val operationId: String,
    val role: String,
    val deviceAlias: String?,
    val commandsHash: String,
    val exp: Long,
    val nonce: String,
)

sealed interface Verification {
    data class Valid(val payload: ApprovalPayload) : Verification
    data class Invalid(val reason: String) : Verification
}
