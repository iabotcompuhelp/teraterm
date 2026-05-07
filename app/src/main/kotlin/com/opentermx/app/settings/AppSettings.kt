package com.opentermx.app.settings

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AppSettings(
    val theme: String = "DARK",
    val locale: String = "es",
    val terminalFontFamily: String = "Consolas",
    val terminalFontSize: Double = 14.0,
    val terminalScrollbackLimit: Int = 10_000,
    val accelerators: Map<String, String> = DEFAULT_ACCELERATORS,
    val recentHosts: List<String> = emptyList(),
    val historyEnabled: Boolean = true,
    val terminal: TerminalSettings = TerminalSettings(),
    val window: WindowSettings = WindowSettings(),
    val keyboard: KeyboardSettings = KeyboardSettings(),
    val proxy: ProxySettings = ProxySettings(),
    val sshGeneral: SshGeneralSettings = SshGeneralSettings(),
    val sshAuth: SshAuthSettings = SshAuthSettings(),
    val tcpIp: TcpIpSettings = TcpIpSettings(),
    val general: GeneralSettings = GeneralSettings(),
    val additional: AdditionalSettings = AdditionalSettings(),
) {
    companion object {
        val DEFAULT_ACCELERATORS: Map<String, String> = linkedMapOf(
            "file.newSession" to "Ctrl+N",
            "file.serial" to "Ctrl+Shift+S",
            "file.ssh" to "Ctrl+Shift+H",
            "edit.copy" to "Ctrl+Shift+C",
            "edit.paste" to "Ctrl+Shift+V",
            "setup.macros" to "Ctrl+M",
            "window.closeTab" to "Ctrl+W",
            "file.exit" to "Ctrl+Q",
        )
    }
}

data class TerminalSettings(
    val cols: Int = 80,
    val rows: Int = 24,
    val cursorStyle: String = "BLOCK",   // BLOCK | BAR | UNDERLINE
    val cursorBlink: Boolean = true,
    val encoding: String = "UTF-8",
    val newlineMode: String = "CRLF",    // CR | LF | CRLF
    val localEcho: Boolean = false,
    val scrollMode: String = "JUMP",     // JUMP | SMOOTH
)

data class WindowSettings(
    val titlePrefix: String = "COMPUHELP",
    val transparency: Double = 1.0,      // 0.3..1.0
    val hideTitleBar: Boolean = false,
    val mouseCursorMode: String = "DEFAULT", // DEFAULT | TEXT | NONE
    /**
     * Hex `#rrggbb` overrides for terminal text/background colours. Blank means "follow the
     * current theme"; a non-blank value persists across theme switches so the user-picked
     * colour wins until they explicitly clear the override.
     */
    val terminalForeground: String = "",
    val terminalBackground: String = "",
)

/**
 * VT-style keyboard behaviour (Setup → Keyboard…). Independent from menu accelerators
 * (which live in `accelerators` / `Setup → Shortcuts…`). Defaults match xterm conventions:
 * Backspace transmits DEL (0x7F), Delete transmits ESC[3~, Alt prefixes ESC.
 */
data class KeyboardSettings(
    val backspaceSendsDel: Boolean = true,   // true → 0x7F (xterm/Linux); false → 0x08 (BS, classic Windows)
    val deleteSendsBs: Boolean = false,      // true → 0x08 (legacy); false → ESC[3~ (xterm)
    val metaSendsEscape: Boolean = true,     // true → Alt+key emits ESC+key (xterm); false → ignored
)

data class ProxySettings(
    val type: String = "NONE",           // NONE | HTTP | SOCKS4 | SOCKS5 | TELNET
    val host: String = "",
    val port: Int = 1080,
    val username: String = "",
    val password: String = "",
)

data class SshGeneralSettings(
    val compression: Boolean = false,
    val ciphers: List<String> = listOf(
        "aes256-gcm@openssh.com",
        "aes128-gcm@openssh.com",
        "chacha20-poly1305@openssh.com",
        "aes256-ctr",
        "aes128-ctr",
    ),
    val kex: List<String> = listOf(
        "curve25519-sha256",
        "diffie-hellman-group-exchange-sha256",
    ),
    val macs: List<String> = listOf(
        "hmac-sha2-256-etm@openssh.com",
        "hmac-sha2-512-etm@openssh.com",
    ),
    val heartbeatSeconds: Int = 60,
)

data class SshAuthSettings(
    val method: String = "PASSWORD",     // PASSWORD | PUBLIC_KEY | KEYBOARD_INTERACTIVE
    val privateKeyPath: String = "",
    val defaultUsername: String = "",
    val tryAgentFirst: Boolean = true,
)

data class TcpIpSettings(
    val keepAlive: Boolean = true,
    val keepAliveSeconds: Int = 60,
    val recvBufferSize: Int = 8192,
    val dnsMode: String = "AUTO",        // AUTO | IPV4 | IPV6
    val telnetPort: Int = 23,
    val terminalType: String = "xterm-256color",
)

data class GeneralSettings(
    val workingDirectory: String = System.getProperty("user.home"),
    val closeBehavior: String = "PROMPT", // PROMPT | AUTO_CLOSE | KEEP_OPEN
    val associateTtl: Boolean = false,
    val autoUpdateCheck: Boolean = true,
)

data class AdditionalSettings(
    val showNotifications: Boolean = true,
    val copyOnSelect: Boolean = false,
    val blinkText: Boolean = true,
    val visualCursorBlink: Boolean = true,
    val defaultLogFormat: String = "TXT",  // TXT | HTML | RAW
    val defaultLogDir: String = System.getProperty("user.home"),
    val autoLogOnConnect: Boolean = false,
    val defaultLogTimestamps: Boolean = true,
    val defaultLogTimestampPattern: String = "yyyy-MM-dd HH:mm:ss.SSS",
    val defaultLogRotation: String = "NONE",      // NONE | BY_SIZE | BY_TIME
    val defaultLogRotationSizeMb: Int = 10,
    val defaultLogRotationMinutes: Int = 60,
    val autoLoginMacroPath: String = "",
    val tftpDefaultPort: Int = 69,
    val tftpDefaultRoot: String = System.getProperty("user.home"),
    val tftpDefaultBlocksize: Int = 512,
    val tftpCsvLogPath: String = "",
)