package com.opentermx.app.settings

import com.fasterxml.jackson.annotation.JsonInclude
import com.opentermx.common.connection.ConnectionConfig
import com.opentermx.common.connection.PortForward
import com.opentermx.common.connection.ProxyConfig
import com.opentermx.common.connection.SerialConfig
import com.opentermx.common.connection.SshAuth
import com.opentermx.common.connection.SshConfig
import com.opentermx.common.connection.TcpRawConfig
import com.opentermx.common.connection.TelnetConfig
import java.time.Instant

/**
 * Tera Term-style setup snapshot. Holds the full {@link AppSettings} plus an optional saved
 * session so "Save setup…" can capture the active connection and "Restore setup…" can re-open it.
 *
 * <p>Secrets are deliberately redacted on save: SSH passwords and key passphrases are NEVER
 * serialized. The user re-enters them on restore (or the SSH agent / public key handles auth).
 *
 * <p>Connection state that comes from globally-shared groups (ciphers, kex, macs, proxy, log
 * defaults) is NOT duplicated on the saved session — it's reconstructed from the imported
 * `settings` so updating the global preference automatically benefits restored sessions.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SetupSnapshot(
    val version: Int = 1,
    val savedAt: String = Instant.now().toString(),
    val settings: AppSettings,
    val savedSession: SavedSession? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SavedSession(
    val type: String,
    val displayName: String,
    val ssh: SshDto? = null,
    val telnet: TelnetDto? = null,
    val tcpRaw: TcpRawDto? = null,
    val serial: SerialDto? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SshDto(
    val host: String,
    val port: Int = 22,
    val username: String = "",
    val authType: String = "PASSWORD",   // PASSWORD | PUBLIC_KEY
    val privateKeyPath: String = "",
    val keepAliveSeconds: Int = 60,
    val agentForwarding: Boolean = false,
    val tryAgentFirst: Boolean = false,
    val terminalType: String = "xterm-256color",
    val portForwards: List<PortForwardDto> = emptyList(),
    val autoLoginMacroPath: String = "",
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TelnetDto(
    val host: String,
    val port: Int = 23,
    val useTls: Boolean = false,
    val terminalType: String = "xterm-256color",
    val keepAlive: Boolean = true,
    val recvBufferSize: Int = 0,
    val dnsMode: String = "AUTO",
    val autoLoginMacroPath: String = "",
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TcpRawDto(
    val host: String,
    val port: Int,
    val keepAlive: Boolean = true,
    val recvBufferSize: Int = 0,
    val dnsMode: String = "AUTO",
    val autoLoginMacroPath: String = "",
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SerialDto(
    val portName: String,
    val baudRate: Int = 9600,
    val dataBits: Int = 8,
    val stopBits: String = "ONE",
    val parity: String = "NONE",
    val flowControl: String = "NONE",
    val autoLoginMacroPath: String = "",
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PortForwardDto(
    val direction: String,        // LOCAL | REMOTE
    val bindAddress: String = "",
    val bindPort: Int,
    val targetHost: String,
    val targetPort: Int,
)

object SnapshotConverters {

    fun build(settings: AppSettings, activeConfig: ConnectionConfig?): SetupSnapshot =
        SetupSnapshot(
            settings = settings,
            savedSession = activeConfig?.let { sessionFromConfig(it) },
        )

    fun sessionFromConfig(config: ConnectionConfig): SavedSession = when (config) {
        is SshConfig -> SavedSession(
            type = "SSH",
            displayName = config.displayName,
            ssh = SshDto(
                host = config.host,
                port = config.port,
                username = config.username,
                authType = when (config.auth) {
                    is SshAuth.Password -> "PASSWORD"
                    is SshAuth.PublicKey -> "PUBLIC_KEY"
                },
                privateKeyPath = (config.auth as? SshAuth.PublicKey)?.privateKeyPath.orEmpty(),
                keepAliveSeconds = config.keepAliveSeconds,
                agentForwarding = config.agentForwarding,
                tryAgentFirst = config.tryAgentFirst,
                terminalType = config.terminalType,
                portForwards = config.portForwards.map { it.toDto() },
                autoLoginMacroPath = config.autoLoginMacroPath,
            ),
        )
        is TelnetConfig -> SavedSession(
            type = "TELNET",
            displayName = config.displayName,
            telnet = TelnetDto(
                host = config.host,
                port = config.port,
                useTls = config.useTls,
                terminalType = config.terminalType,
                keepAlive = config.keepAlive,
                recvBufferSize = config.recvBufferSize,
                dnsMode = config.dnsMode,
                autoLoginMacroPath = config.autoLoginMacroPath,
            ),
        )
        is TcpRawConfig -> SavedSession(
            type = "TCP_RAW",
            displayName = config.displayName,
            tcpRaw = TcpRawDto(
                host = config.host,
                port = config.port,
                keepAlive = config.keepAlive,
                recvBufferSize = config.recvBufferSize,
                dnsMode = config.dnsMode,
                autoLoginMacroPath = config.autoLoginMacroPath,
            ),
        )
        is SerialConfig -> SavedSession(
            type = "SERIAL",
            displayName = config.displayName,
            serial = SerialDto(
                portName = config.portName,
                baudRate = config.baudRate,
                dataBits = config.dataBits,
                stopBits = config.stopBits.name,
                parity = config.parity.name,
                flowControl = config.flowControl.name,
                autoLoginMacroPath = config.autoLoginMacroPath,
            ),
        )
    }

    /**
     * Materialises a saved session back into a {@link ConnectionConfig}, splicing in the
     * globally-shared groups (proxy, ssh general, etc.) from the supplied settings so the
     * restored session reflects the current global preferences.
     */
    fun configFromSession(session: SavedSession, settings: AppSettings): ConnectionConfig? {
        val proxy = ProxyConfig(
            type = runCatching { ProxyConfig.Type.valueOf(settings.proxy.type) }
                .getOrDefault(ProxyConfig.Type.NONE),
            host = settings.proxy.host,
            port = settings.proxy.port,
            username = settings.proxy.username,
            password = settings.proxy.password,
        )
        return when (session.type.uppercase()) {
            "SSH" -> session.ssh?.toConfig(settings, proxy)
            "TELNET" -> session.telnet?.toConfig(proxy)
            "TCP_RAW" -> session.tcpRaw?.toConfig(proxy)
            "SERIAL" -> session.serial?.toConfig()
            else -> null
        }
    }
}

private fun PortForward.toDto(): PortForwardDto = PortForwardDto(
    direction = direction.name,
    bindAddress = bindAddress,
    bindPort = bindPort,
    targetHost = targetHost,
    targetPort = targetPort,
)

private fun PortForwardDto.toModel(): PortForward = PortForward(
    direction = PortForward.Direction.valueOf(direction),
    bindAddress = bindAddress,
    bindPort = bindPort,
    targetHost = targetHost,
    targetPort = targetPort,
)

private fun SshDto.toConfig(settings: AppSettings, proxy: ProxyConfig): SshConfig = SshConfig(
    host = host,
    username = username,
    auth = if (authType == "PUBLIC_KEY") SshAuth.PublicKey(privateKeyPath)
           else SshAuth.Password(CharArray(0)),
    port = port,
    keepAliveSeconds = keepAliveSeconds,
    agentForwarding = agentForwarding,
    tryAgentFirst = tryAgentFirst,
    portForwards = portForwards.map { it.toModel() },
    compression = settings.sshGeneral.compression,
    ciphers = settings.sshGeneral.ciphers,
    kex = settings.sshGeneral.kex,
    macs = settings.sshGeneral.macs,
    terminalType = terminalType.ifBlank { settings.tcpIp.terminalType },
    proxy = proxy,
    autoLoginMacroPath = autoLoginMacroPath,
)

private fun TelnetDto.toConfig(proxy: ProxyConfig): TelnetConfig = TelnetConfig(
    host = host,
    port = port,
    useTls = useTls,
    terminalType = terminalType,
    keepAlive = keepAlive,
    recvBufferSize = recvBufferSize,
    proxy = proxy,
    dnsMode = dnsMode,
    autoLoginMacroPath = autoLoginMacroPath,
)

private fun TcpRawDto.toConfig(proxy: ProxyConfig): TcpRawConfig = TcpRawConfig(
    host = host,
    port = port,
    keepAlive = keepAlive,
    recvBufferSize = recvBufferSize,
    proxy = proxy,
    dnsMode = dnsMode,
    autoLoginMacroPath = autoLoginMacroPath,
)

private fun SerialDto.toConfig(): SerialConfig = SerialConfig(
    portName = portName,
    baudRate = baudRate,
    dataBits = dataBits,
    stopBits = SerialConfig.StopBits.valueOf(stopBits),
    parity = SerialConfig.Parity.valueOf(parity),
    flowControl = SerialConfig.FlowControl.valueOf(flowControl),
    autoLoginMacroPath = autoLoginMacroPath,
)