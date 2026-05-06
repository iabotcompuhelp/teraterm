package com.opentermx.common.connection

sealed interface ConnectionConfig {
    val type: ConnectionType
    val displayName: String
}

/**
 * Outgoing-connection proxy configuration. NONE means "no proxy" — the connection layer treats
 * `type == NONE` or a blank host as a no-op and connects directly. Username/password are only
 * honoured by transports that support proxy auth (jsch ProxyHTTP/SOCKS, SOCKS via java.net.Authenticator).
 */
data class ProxyConfig(
    val type: Type = Type.NONE,
    val host: String = "",
    val port: Int = 1080,
    val username: String = "",
    val password: String = "",
) {
    enum class Type { NONE, HTTP, SOCKS4, SOCKS5 }

    val isEnabled: Boolean get() = type != Type.NONE && host.isNotBlank()
}

data class SerialConfig(
    val portName: String,
    val baudRate: Int = 9600,
    val dataBits: Int = 8,
    val stopBits: StopBits = StopBits.ONE,
    val parity: Parity = Parity.NONE,
    val flowControl: FlowControl = FlowControl.NONE,
) : ConnectionConfig {
    override val type: ConnectionType get() = ConnectionType.SERIAL
    override val displayName: String get() = "$portName @ $baudRate"

    enum class StopBits { ONE, ONE_AND_HALF, TWO }
    enum class Parity { NONE, ODD, EVEN, MARK, SPACE }
    enum class FlowControl { NONE, RTS_CTS, XON_XOFF }
}

data class SshConfig @JvmOverloads constructor(
    val host: String,
    val username: String,
    val auth: SshAuth,
    val port: Int = 22,
    val keepAliveSeconds: Int = 60,
    val agentForwarding: Boolean = false,
    val tryAgentFirst: Boolean = false,
    val portForwards: List<PortForward> = emptyList(),
    val compression: Boolean = false,
    val ciphers: List<String> = emptyList(),
    val kex: List<String> = emptyList(),
    val macs: List<String> = emptyList(),
    val terminalType: String = "xterm-256color",
    val proxy: ProxyConfig = ProxyConfig(),
) : ConnectionConfig {
    override val type: ConnectionType get() = ConnectionType.SSH
    override val displayName: String get() = "$username@$host:$port"
}

sealed interface SshAuth {
    data class Password(val password: CharArray) : SshAuth
    data class PublicKey(val privateKeyPath: String, val passphrase: CharArray? = null) : SshAuth
}

data class TelnetConfig @JvmOverloads constructor(
    val host: String,
    val port: Int = 23,
    val useTls: Boolean = false,
    val terminalType: String = "xterm-256color",
    val keepAlive: Boolean = true,
    val recvBufferSize: Int = 0,
    val proxy: ProxyConfig = ProxyConfig(),
) : ConnectionConfig {
    override val type: ConnectionType get() = ConnectionType.TELNET
    override val displayName: String get() = "telnet://$host:$port"
}

data class TcpRawConfig @JvmOverloads constructor(
    val host: String,
    val port: Int,
    val keepAlive: Boolean = true,
    val recvBufferSize: Int = 0,
    val proxy: ProxyConfig = ProxyConfig(),
) : ConnectionConfig {
    override val type: ConnectionType get() = ConnectionType.TCP_RAW
    override val displayName: String get() = "$host:$port"
}
