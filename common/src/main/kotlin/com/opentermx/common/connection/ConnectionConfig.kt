package com.opentermx.common.connection

sealed interface ConnectionConfig {
    val type: ConnectionType
    val displayName: String
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

data class SshConfig(
    val host: String,
    val username: String,
    val auth: SshAuth,
    val port: Int = 22,
    val keepAliveSeconds: Int = 60,
    val agentForwarding: Boolean = false,
    val portForwards: List<PortForward> = emptyList(),
) : ConnectionConfig {
    override val type: ConnectionType get() = ConnectionType.SSH
    override val displayName: String get() = "$username@$host:$port"
}

sealed interface SshAuth {
    data class Password(val password: CharArray) : SshAuth
    data class PublicKey(val privateKeyPath: String, val passphrase: CharArray? = null) : SshAuth
}

data class TelnetConfig(
    val host: String,
    val port: Int = 23,
    val useTls: Boolean = false,
) : ConnectionConfig {
    override val type: ConnectionType get() = ConnectionType.TELNET
    override val displayName: String get() = "telnet://$host:$port"
}

data class TcpRawConfig(
    val host: String,
    val port: Int,
) : ConnectionConfig {
    override val type: ConnectionType get() = ConnectionType.TCP_RAW
    override val displayName: String get() = "$host:$port"
}
