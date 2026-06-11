package com.opentermx.app.settings

import com.fasterxml.jackson.annotation.JsonInclude
import com.opentermx.app.ui.terminal.highlight.HighlightSettings

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
    val aiAssistant: AiAssistantSettings = AiAssistantSettings(),
    val restApi: RestApiPersistedSettings = RestApiPersistedSettings(),
    /**
     * PostgreSQL de telemetría (Fase 3): histórico de métricas de interfaces, eventos de
     * enlace, snapshots de config y auditoría. Default OFF; la app degrada con gracia
     * si la BD no responde.
     */
    val database: DatabaseSettings = DatabaseSettings(),
    /**
     * Integraciones read-only a plataformas de monitoreo (Fase 4): Zabbix / OpManager.
     * Las consumen las tools MCP `zabbix_*` / `opmanager_*`.
     */
    val monitoringIntegrations: List<MonitoringIntegrationSetting> = emptyList(),
    /**
     * Fingerprinting de dispositivos (Fase 5): dry-run y pruebas activas de rol. Lo
     * consumen `refresh_device_fingerprint` y el enriquecimiento de `list_sessions`.
     */
    val fingerprint: FingerprintSettings = FingerprintSettings(),
    /**
     * Credenciales recordadas para conexiones SSH previas. Cada entrada lleva
     * `host/port/username` y opcionalmente password o ruta de clave + passphrase, cifrada
     * con [com.opentermx.common.crypto.SecretCipher]. Se popula al conectar con la opción
     * "Recordar credenciales" tildada en [com.opentermx.app.ui.dialog.SshConfigDialog]; se
     * consulta en `MainWindow.seedSshConfig` para autocompletar el dialog.
     */
    val savedConnections: List<SavedConnection> = emptyList(),
    /**
     * Configuración del resaltado visual contextual del terminal (Fase 1: keywords +
     * prompt detection con auto-skip en alternate screen buffer). Settings sub-objeto
     * para que el JSON quede prolijo y permita agregar campos sin migrar.
     */
    val highlight: HighlightSettings = HighlightSettings(),
) {
    companion object {
        val DEFAULT_ACCELERATORS: Map<String, String> = linkedMapOf(
            "file.newSession" to "Ctrl+N",
            "file.serial" to "Ctrl+Shift+S",
            "file.ssh" to "Ctrl+Shift+H",
            "edit.copy" to "Ctrl+Shift+C",
            "edit.paste" to "Ctrl+Shift+V",
            "setup.macros" to "Ctrl+M",
            "setup.aiAssistant" to "Ctrl+Alt+A",
            "setup.restApi" to "Ctrl+Alt+R",
            "window.toggleAiChat" to "Ctrl+I",
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
    // Defaults amplios para cubrir equipos enterprise modernos y legacy a la vez.
    // Phase 2.5 T2 expandió kex/ciphers/macs porque los defaults restrictivos
    // hacían fallar la negociación contra Cisco IOS reciente, equipos FIPS y
    // hardware federal que sólo aceptan ECDH NIST o cipher suites clásicas.
    val ciphers: List<String> = listOf(
        "aes256-gcm@openssh.com",
        "aes128-gcm@openssh.com",
        "aes256-gcm",
        "aes128-gcm",
        "chacha20-poly1305@openssh.com",
        "aes256-ctr",
        "aes192-ctr",
        "aes128-ctr",
    ),
    val kex: List<String> = listOf(
        "curve25519-sha256",
        "curve25519-sha256@libssh.org",
        "ecdh-sha2-nistp256",
        "ecdh-sha2-nistp384",
        "ecdh-sha2-nistp521",
        "diffie-hellman-group-exchange-sha256",
        "diffie-hellman-group16-sha512",
        "diffie-hellman-group14-sha256",
    ),
    val macs: List<String> = listOf(
        "hmac-sha2-256-etm@openssh.com",
        "hmac-sha2-512-etm@openssh.com",
        "hmac-sha2-256",
        "hmac-sha2-512",
        "hmac-sha1",
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
    /**
     * Backend de E/S serial: "JSERIALCOMM" (default, jSerialComm puro Java) o "NATIVE"
     * (librería nativa opentermx_native vía JNA). La system property
     * `-Dopentermx.serial.backend` sigue ganando si está fijada.
     */
    val serialBackend: String = "JSERIALCOMM",
    /**
     * Motor VT que usa el `TerminalView`: "KOTLIN" (default, parser ANSI puro Kotlin) o
     * "NATIVE" (emulador C de opentermx_native vía JNA). Aplica a pestañas nuevas — las
     * abiertas conservan el motor con el que se crearon. La system property
     * `-Dopentermx.terminal.engine` sigue ganando si está fijada.
     */
    val terminalEngine: String = "KOTLIN",
    /**
     * Si está activo, el cliente Telnet registra un `spyStream` a `System.err` que
     * loguea la negociación IAC (WILL/WONT/DO/DONT) en ambas direcciones. Usado para
     * diagnosticar fallas tipo "TCP conecta pero la sesión nunca muestra prompt"
     * (Phase 2.5 T3, validación de la hipótesis de `EchoOptionHandler`). Off por default
     * porque ensucia logs en prod.
     */
    val telnetVerboseLog: Boolean = false,
    /**
     * "Modo terminal" — cuando es `true`, la UI oculta los accesos a IA/MCP/REST
     * (menús, status bar, panel de chat) y se inhibe el auto-boot de esos
     * servicios. Toggle desde Setup → Modo terminal…, protegido con PIN.
     */
    val terminalOnlyMode: Boolean = false,
    /**
     * Hash PBKDF2-WithHmacSHA256 (Base64) del PIN que protege el toggle de
     * `terminalOnlyMode`. `null` cuando nunca se fijó un PIN. Junto con
     * `terminalOnlyPinSalt` define la credencial de bloqueo/desbloqueo.
     */
    val terminalOnlyPinHash: String? = null,
    /**
     * Salt aleatorio (Base64, 16 bytes) usado para derivar `terminalOnlyPinHash`.
     * Generado al fijar el PIN por primera vez.
     */
    val terminalOnlyPinSalt: String? = null,
)