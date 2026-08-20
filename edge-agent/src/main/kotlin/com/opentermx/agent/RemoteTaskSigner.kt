package com.opentermx.agent

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class RemoteTaskSigner(secret: ByteArray) {
    private val key = secret.copyOf()

    init { require(key.size >= 16) { "El secreto de firma debe tener al menos 16 bytes" } }

    fun sign(task: RemoteCommandTask): RemoteCommandTask = task.copy(signature = signatureOf(task))

    fun verify(task: RemoteCommandTask): Boolean {
        val supplied = runCatching { Base64.getUrlDecoder().decode(task.signature) }.getOrNull() ?: return false
        val expected = Base64.getUrlDecoder().decode(signatureOf(task))
        return MessageDigest.isEqual(expected, supplied)
    }

    private fun signatureOf(task: RemoteCommandTask): String {
        val canonical = buildString {
            field(task.protocolVersion.toString()); field(task.taskId); field(task.operationId.orEmpty())
            field(task.agentId); field(task.sessionId); field(task.rationale)
            field(task.createdAtMillis.toString()); field(task.expiresAtMillis.toString())
            task.commands.forEach { field(it) }
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun StringBuilder.field(value: String) {
        append(value.toByteArray(StandardCharsets.UTF_8).size).append(':').append(value).append('|')
    }
}
