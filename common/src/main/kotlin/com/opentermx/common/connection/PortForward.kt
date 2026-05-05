package com.opentermx.common.connection

/**
 * SSH TCP forwarding rule. The interpretation of the four address fields
 * depends on [direction]:
 *
 *   LOCAL  (`ssh -L`): client opens [bindAddress]:[bindPort] locally and
 *                      tunnels each connection to [targetHost]:[targetPort]
 *                      from the SSH server.
 *   REMOTE (`ssh -R`): server opens [bindAddress]:[bindPort] on the remote
 *                      side and tunnels each connection to [targetHost]:[targetPort]
 *                      reachable from the client.
 *
 * `bindAddress` may be empty / "0.0.0.0" / "*" / "localhost"; the underlying
 * jsch session has its own defaults when blank.
 */
data class PortForward(
    val direction: Direction,
    val bindAddress: String,
    val bindPort: Int,
    val targetHost: String,
    val targetPort: Int,
) {
    enum class Direction { LOCAL, REMOTE }

    fun describe(): String {
        val bind = if (bindAddress.isBlank()) "*" else bindAddress
        val arrow = if (direction == Direction.LOCAL) "→" else "←"
        return "[${direction.name}] $bind:$bindPort $arrow $targetHost:$targetPort"
    }
}