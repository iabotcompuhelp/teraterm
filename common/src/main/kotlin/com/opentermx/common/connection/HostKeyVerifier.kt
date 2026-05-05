package com.opentermx.common.connection

fun interface HostKeyVerifier {
    fun verify(prompt: HostKeyPrompt): HostKeyDecision
}

data class HostKeyPrompt(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprintSha256: String,
    val status: HostKeyStatus,
    val previousFingerprints: List<String> = emptyList(),
)

enum class HostKeyStatus {
    /** Host has no known key. */
    NEW,

    /** Host has a known key but the server presented a different one. */
    CHANGED,
}

enum class HostKeyDecision {
    /** Trust this key now and persist it to the known_hosts file. */
    ACCEPT_AND_SAVE,

    /** Trust this key for this connection only; do not persist. */
    ACCEPT_ONCE,

    /** Refuse the key; the connection must abort. */
    REJECT,
}

object AutoAcceptHostKeyVerifier : HostKeyVerifier {
    override fun verify(prompt: HostKeyPrompt): HostKeyDecision = HostKeyDecision.ACCEPT_AND_SAVE
}

object RejectAllHostKeyVerifier : HostKeyVerifier {
    override fun verify(prompt: HostKeyPrompt): HostKeyDecision = HostKeyDecision.REJECT
}