package com.opentermx.app.ui

import com.opentermx.app.i18n.Strings
import com.opentermx.app.settings.AppSettings
import com.opentermx.app.settings.SavedAuthKind
import com.opentermx.app.settings.SavedConnection
import com.opentermx.app.settings.SavedConnections
import com.opentermx.app.ui.dialog.SshConfigDialog
import com.opentermx.common.connection.Connection
import com.opentermx.common.connection.ConnectionConfig
import com.opentermx.common.connection.HostKeyVerifier
import com.opentermx.common.connection.ProxyConfig
import com.opentermx.common.connection.SerialConfig
import com.opentermx.common.connection.SshAuth
import com.opentermx.common.connection.SshConfig
import com.opentermx.common.connection.TcpRawConfig
import com.opentermx.common.connection.TelnetConfig
import com.opentermx.common.crypto.SecretCipher
import com.opentermx.serial.SerialConnectionFactory
import com.opentermx.ssh.SshConnection
import com.opentermx.telnet.RawTcpConnection
import com.opentermx.telnet.TelnetConnection
import javafx.stage.Stage
import org.slf4j.LoggerFactory

/**
 * Todo el ciclo de vida de las "Conexiones guardadas": persistir/eliminar la decisión
 * "Recordar" de cada protocolo (SSH/Telnet/WEB/RDP), editar y borrar desde el panel
 * lateral, quick-connect con doble-click, el seed de configs desde Setup, y el
 * historial de hosts recientes. Extraído de `MainWindow` (split 2026-06).
 *
 * Los secretos van SIEMPRE cifrados con [SecretCipher] dentro de `SavedConnection.secret`;
 * el plaintext sólo existe transitoriamente para pre-cargar dialogs o conectar.
 */
class SavedConnectionsCoordinator(
    private val stage: Stage,
    private val settings: () -> AppSettings,
    private val persist: ((AppSettings) -> AppSettings) -> Unit,
    private val hostKeyVerifier: HostKeyVerifier,
    private val refreshList: () -> Unit,
    private val setStatus: (String) -> Unit,
    private val openSession: (ConnectionConfig, String, Connection) -> Unit,
    private val openWebSession: (url: String, label: String, user: String, pass: String, autofill: Boolean) -> Unit,
    private val launchRdpSession: (host: String, port: Int, user: String, pass: String) -> Unit,
    private val resolveSerialBackend: () -> SerialConnectionFactory.Backend,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ---------------------------------------------------------------- seeds

    /**
     * Builds an SshConfig prefilled from `saved` (entrada recordada para este host:port) si
     * existe, sino desde Setup → SSH / SSH Authentication / TCP-IP. El usuario puede sobreescribir
     * cualquier campo en el [SshConfigDialog]; los campos que el dialog no renderiza pasan
     * sin cambios.
     */
    fun seedSshConfig(host: String, port: Int, saved: SavedConnection? = null): SshConfig {
        val s = settings()
        val auth = s.sshAuth
        val gen = s.sshGeneral
        val username = saved?.username?.takeIf { it.isNotBlank() } ?: auth.defaultUsername
        val authObj: SshAuth = when {
            saved?.authKind == SavedAuthKind.PASSWORD -> {
                val plain = saved.secret?.let { runCatching { SecretCipher.decrypt(it) }.getOrNull() }
                SshAuth.Password((plain ?: "").toCharArray())
            }
            saved?.authKind == SavedAuthKind.SSH_KEY && !saved.keyPath.isNullOrBlank() -> {
                val passphrase = saved.secret?.let { runCatching { SecretCipher.decrypt(it) }.getOrNull() }
                SshAuth.PublicKey(saved.keyPath, passphrase?.takeIf { it.isNotEmpty() }?.toCharArray())
            }
            auth.method == "PUBLIC_KEY" && auth.privateKeyPath.isNotBlank() -> SshAuth.PublicKey(auth.privateKeyPath)
            else -> SshAuth.Password(CharArray(0))
        }
        return SshConfig(
            host = host,
            username = username,
            auth = authObj,
            port = port,
            keepAliveSeconds = gen.heartbeatSeconds,
            agentForwarding = false,
            tryAgentFirst = auth.tryAgentFirst,
            portForwards = emptyList(),
            compression = gen.compression,
            ciphers = gen.ciphers,
            kex = gen.kex,
            macs = gen.macs,
            terminalType = s.tcpIp.terminalType,
            proxy = currentProxyConfig(),
        )
    }

    fun currentProxyConfig(): ProxyConfig {
        val p = settings().proxy
        val type = runCatching { ProxyConfig.Type.valueOf(p.type) }.getOrDefault(ProxyConfig.Type.NONE)
        return ProxyConfig(
            type = type,
            host = p.host,
            port = p.port,
            username = p.username,
            password = p.password,
        )
    }

    private fun telnetConfigFor(host: String, port: Int): TelnetConfig {
        val s = settings()
        return TelnetConfig(
            host = host,
            port = port,
            terminalType = s.tcpIp.terminalType,
            keepAlive = s.tcpIp.keepAlive,
            recvBufferSize = s.tcpIp.recvBufferSize,
            proxy = currentProxyConfig(),
            dnsMode = s.tcpIp.dnsMode,
        )
    }

    // ----------------------------------------------------- persist decisions

    /**
     * Persiste o elimina la `SavedConnection` para (cfg.host, cfg.port, cfg.username) según la
     * decisión del checkbox "Recordar credenciales" del [SshConfigDialog].
     *  - `remember = true`: cifra password/passphrase con SecretCipher, upsertea con timestamp.
     *  - `remember = false`: si había entradas para `(host, port)` las elimina (el usuario
     *    explícitamente destildó). Mantenemos la simetría: tildar/destildar = on/off.
     */
    fun persistSavedConnectionDecision(cfg: SshConfig, remember: Boolean, label: String) {
        val current = settings().savedConnections
        val updated = if (remember) {
            val entry = buildSavedConnectionFrom(cfg, label)
            SavedConnections.upsert(current, entry, bumpLastUsed = true)
        } else {
            SavedConnections.removeByHost(current, protocol = "SSH", host = cfg.host, port = cfg.port)
        }
        applyIfChanged(current, updated)
    }

    /**
     * Persiste o elimina una entrada Telnet. Telnet no tiene auth en el protocolo, así que
     * `authKind = NONE` y no hay secret ni keyPath. `username` se guarda como referencia para
     * que el operador recuerde con qué login entra (no se transmite).
     */
    fun persistSavedTelnet(cfg: TelnetConfig, remember: Boolean, label: String, username: String) {
        val current = settings().savedConnections
        val updated = if (remember) {
            val entry = SavedConnection(
                id = java.util.UUID.randomUUID().toString(),
                protocol = "TELNET",
                host = cfg.host,
                port = cfg.port,
                username = username,
                authKind = SavedAuthKind.NONE,
                label = label,
            )
            SavedConnections.upsert(current, entry, bumpLastUsed = true)
        } else {
            SavedConnections.removeByHost(current, protocol = "TELNET", host = cfg.host, port = cfg.port)
        }
        applyIfChanged(current, updated)
    }

    /**
     * Persiste/elimina una entrada WEB siguiendo la misma simetría que SSH/Telnet: si el
     * usuario tildó "Recordar", upsert; si destildó y había entries, removeByHost (port = 0).
     * `host` lleva la URL completa (porque `protocol = "WEB"` no usa el campo `port`).
     */
    fun persistSavedWeb(result: com.opentermx.app.ui.dialog.WebConfigDialog.Result) {
        val current = settings().savedConnections
        val updated = if (result.remember) {
            val secret = result.password.takeIf { it.isNotEmpty() }?.let { SecretCipher.encrypt(it) }
            val entry = SavedConnection(
                id = java.util.UUID.randomUUID().toString(),
                protocol = "WEB",
                host = result.url,
                port = 0,
                username = result.username,
                authKind = if (secret != null) SavedAuthKind.PASSWORD else SavedAuthKind.NONE,
                secret = secret,
                label = result.label,
            )
            SavedConnections.upsert(current, entry, bumpLastUsed = true)
        } else {
            SavedConnections.removeByHost(current, protocol = "WEB", host = result.url, port = 0)
        }
        applyIfChanged(current, updated)
    }

    /**
     * Persiste o elimina una entrada RDP siguiendo la simetría on/off de SSH/Telnet/WEB.
     * `host` lleva el hostname/IP; `port` el TCP port; password cifrada en `secret`.
     */
    fun persistSavedRdp(result: com.opentermx.app.ui.dialog.RdpConfigDialog.Result) {
        val current = settings().savedConnections
        val updated = if (result.remember) {
            val secret = result.password.takeIf { it.isNotEmpty() }?.let { SecretCipher.encrypt(it) }
            val entry = SavedConnection(
                id = java.util.UUID.randomUUID().toString(),
                protocol = "RDP",
                host = result.host,
                port = result.port,
                username = result.username,
                authKind = if (secret != null) SavedAuthKind.PASSWORD else SavedAuthKind.NONE,
                secret = secret,
                label = result.label,
            )
            SavedConnections.upsert(current, entry, bumpLastUsed = true)
        } else {
            // Si destildó "Recordar", también limpiamos la cred del Credential Manager si quedó.
            com.opentermx.app.ui.rdp.RdpLauncher.deleteCredential(result.host)
            SavedConnections.removeByHost(current, protocol = "RDP", host = result.host, port = result.port)
        }
        applyIfChanged(current, updated)
    }

    fun buildSavedConnectionFrom(cfg: SshConfig, label: String): SavedConnection {
        val (kind, secret, keyPath) = when (val a = cfg.auth) {
            is SshAuth.Password -> {
                val pw = String(a.password)
                Triple(
                    SavedAuthKind.PASSWORD,
                    if (pw.isEmpty()) null else SecretCipher.encrypt(pw),
                    null,
                )
            }
            is SshAuth.PublicKey -> {
                val pp = a.passphrase?.let { String(it) }.orEmpty()
                Triple(
                    SavedAuthKind.SSH_KEY,
                    if (pp.isEmpty()) null else SecretCipher.encrypt(pp),
                    a.privateKeyPath,
                )
            }
        }
        return SavedConnection(
            id = java.util.UUID.randomUUID().toString(),
            protocol = "SSH",
            host = cfg.host,
            port = cfg.port,
            username = cfg.username,
            authKind = kind,
            secret = secret,
            keyPath = keyPath,
            label = label,
        )
    }

    fun rememberHost(host: String) {
        if (!settings().historyEnabled || host.isBlank()) return
        // Most-recent first, dedup case-insensitive, cap at 20.
        val deduped = (listOf(host) + settings().recentHosts.filterNot { it.equals(host, ignoreCase = true) })
            .take(20)
        persist { it.copy(recentHosts = deduped) }
    }

    // ------------------------------------------------------------ edit/delete

    fun openSavedConnectionsDialog() {
        val dialog = com.opentermx.app.ui.dialog.SavedConnectionsDialog(settings().savedConnections)
        val updated = dialog.showAndWait().orElse(null) ?: return
        applyIfChanged(settings().savedConnections, updated)
    }

    /**
     * Edita una entrada guardada desde el panel lateral (clic-derecho → Modificar…). Reusa el
     * dialog correspondiente al protocolo, pre-cargado con los valores actuales. Al confirmar,
     * reemplaza la entrada vieja por una nueva con los campos actualizados — no abre tab nuevo.
     */
    fun editSavedConnection(saved: SavedConnection) {
        when (saved.protocol) {
            "SSH" -> editSshSaved(saved)
            "TELNET" -> editTelnetSaved(saved)
            "WEB" -> editWebSaved(saved)
            "RDP" -> editRdpSaved(saved)
            else -> log.warn("editSavedConnection: protocolo no soportado '${saved.protocol}'")
        }
    }

    private fun editSshSaved(saved: SavedConnection) {
        val seed = seedSshConfig(saved.host, saved.port, saved)
        val dialog = SshConfigDialog(seed, rememberCredentialsDefault = true, initialLabel = saved.label)
        val cfg = dialog.showAndWait().orElse(null) ?: return
        val newLabel = dialog.labelField.text?.trim().orEmpty()
        // Sacamos primero la entrada vieja (por id) para evitar duplicar si user cambió host/port.
        val withoutOld = SavedConnections.removeById(settings().savedConnections, saved.id)
        val newEntry = buildSavedConnectionFrom(cfg, newLabel)
        val updated = SavedConnections.upsert(withoutOld, newEntry, bumpLastUsed = false)
        persistList(updated)
    }

    private fun editTelnetSaved(saved: SavedConnection) {
        val dialog = com.opentermx.app.ui.dialog.TelnetConfigDialog(
            initial = telnetConfigFor(saved.host, saved.port),
            initialLabel = saved.label,
            initialUsername = saved.username,
            rememberDefault = true,
        )
        val confirmed = dialog.showAndWait().orElse(null) ?: return
        val withoutOld = SavedConnections.removeById(settings().savedConnections, saved.id)
        val newEntry = SavedConnection(
            id = java.util.UUID.randomUUID().toString(),
            protocol = "TELNET",
            host = confirmed.host,
            port = confirmed.port,
            username = dialog.usernameField.text?.trim().orEmpty(),
            authKind = SavedAuthKind.NONE,
            label = dialog.labelField.text?.trim().orEmpty(),
        )
        val updated = SavedConnections.upsert(withoutOld, newEntry, bumpLastUsed = false)
        persistList(updated)
    }

    private fun editWebSaved(saved: SavedConnection) {
        val currentPlain = decryptPasswordOrEmpty(saved)
        val dialog = com.opentermx.app.ui.dialog.WebConfigDialog(
            initialUrl = saved.host,
            initialLabel = saved.label,
            initialUsername = saved.username,
            initialPassword = currentPlain,
            autofillDefault = true,
            rememberDefault = true,
        )
        val result = dialog.showAndWait().orElse(null) ?: return
        val withoutOld = SavedConnections.removeById(settings().savedConnections, saved.id)
        val newSecret = result.password.takeIf { it.isNotEmpty() }?.let { SecretCipher.encrypt(it) }
        val newEntry = SavedConnection(
            id = java.util.UUID.randomUUID().toString(),
            protocol = "WEB",
            host = result.url,
            port = 0,
            username = result.username,
            authKind = if (newSecret != null) SavedAuthKind.PASSWORD else SavedAuthKind.NONE,
            secret = newSecret,
            label = result.label,
        )
        val updated = SavedConnections.upsert(withoutOld, newEntry, bumpLastUsed = false)
        persistList(updated)
    }

    private fun editRdpSaved(saved: SavedConnection) {
        val currentPlain = decryptPasswordOrEmpty(saved)
        val dialog = com.opentermx.app.ui.dialog.RdpConfigDialog(
            initialHost = saved.host,
            initialPort = saved.port,
            initialLabel = saved.label,
            initialUsername = saved.username,
            initialPassword = currentPlain,
            rememberDefault = true,
        )
        val result = dialog.showAndWait().orElse(null) ?: return
        val withoutOld = SavedConnections.removeById(settings().savedConnections, saved.id)
        // Si el host cambió, limpiar cred vieja del Credential Manager.
        if (!result.host.equals(saved.host, ignoreCase = true)) {
            com.opentermx.app.ui.rdp.RdpLauncher.deleteCredential(saved.host)
        }
        val newSecret = result.password.takeIf { it.isNotEmpty() }?.let { SecretCipher.encrypt(it) }
        val newEntry = SavedConnection(
            id = java.util.UUID.randomUUID().toString(),
            protocol = "RDP",
            host = result.host,
            port = result.port,
            username = result.username,
            authKind = if (newSecret != null) SavedAuthKind.PASSWORD else SavedAuthKind.NONE,
            secret = newSecret,
            label = result.label,
        )
        val updated = SavedConnections.upsert(withoutOld, newEntry, bumpLastUsed = false)
        persistList(updated)
    }

    /**
     * Borra una entrada guardada desde el panel lateral (clic-derecho → Eliminar o tecla DELETE).
     * Para entries RDP también limpia la cred del Credential Manager de Windows — simetría
     * on/off: borrar la entrada de OpenTermX también la quita del SO.
     */
    fun deleteSavedConnection(saved: SavedConnection) {
        if (saved.protocol == "RDP") {
            com.opentermx.app.ui.rdp.RdpLauncher.deleteCredential(saved.host)
        }
        val updated = SavedConnections.removeById(settings().savedConnections, saved.id)
        applyIfChanged(settings().savedConnections, updated)
    }

    // ------------------------------------------------------------- connect

    /**
     * Conecta directo desde el panel "Conexiones guardadas" (doble-click). Arma el config
     * sin pasar por el dialog interactivo — todas las credenciales ya están en `saved`. Tras
     * conectar, bumpea `lastUsedAtMillis` para que la entrada suba al tope del listado.
     */
    fun quickConnectSaved(saved: SavedConnection) {
        when (saved.protocol) {
            "SSH" -> {
                val seed = seedSshConfig(saved.host, saved.port, saved)
                openSession(seed, saved.displayLabel(), SshConnection(seed, hostKeyVerifier))
            }
            "TELNET" -> {
                val cfg = telnetConfigFor(saved.host, saved.port)
                openSession(cfg, saved.displayLabel(), TelnetConnection(cfg))
            }
            "WEB" -> {
                // `host` lleva la URL completa; `port` es 0 (no se usa).
                openWebSession(saved.host, saved.displayLabel(), saved.username, decryptPasswordOrEmpty(saved), true)
            }
            "RDP" -> {
                if (!com.opentermx.app.ui.rdp.RdpLauncher.isSupported) {
                    setStatus("RDP solo disponible en Windows")
                    return
                }
                launchRdpSession(saved.host, saved.port, saved.username, decryptPasswordOrEmpty(saved))
            }
            else -> {
                log.warn("quickConnect: protocolo no soportado '${saved.protocol}' para ${saved.host}")
                return
            }
        }
        // Bump del timestamp para que la entrada quede al tope del listado.
        val refreshed = SavedConnections.upsert(settings().savedConnections, saved, bumpLastUsed = true)
        applyIfChanged(settings().savedConnections, refreshed)
        rememberHost(saved.host)
    }

    /** Abre la sesión persistida en un snapshot de setup (Setup → Restaurar…). */
    fun openSavedSession(saved: com.opentermx.app.settings.SavedSession) {
        val cfg = com.opentermx.app.settings.SnapshotConverters.configFromSession(saved, settings())
        if (cfg == null) {
            setStatus(Strings.format("status.setupError", "Unsupported saved session type: ${saved.type}"))
            return
        }
        when (cfg) {
            is SshConfig -> openSession(cfg, "${cfg.username}@${cfg.host}", SshConnection(cfg, hostKeyVerifier))
            is TelnetConfig -> {
                val protocol = if (cfg.useTls) "telnets" else "telnet"
                openSession(cfg, "$protocol://${cfg.host}:${cfg.port}", TelnetConnection(cfg))
            }
            is TcpRawConfig -> openSession(cfg, "${cfg.host}:${cfg.port}", RawTcpConnection(cfg))
            is SerialConfig -> openSession(cfg, cfg.portName, SerialConnectionFactory.create(cfg, resolveSerialBackend()))
        }
    }

    // -------------------------------------------------------------- helpers

    private fun decryptPasswordOrEmpty(saved: SavedConnection): String = saved.secret
        ?.takeIf { saved.authKind == SavedAuthKind.PASSWORD }
        ?.let { runCatching { SecretCipher.decrypt(it) }.getOrNull() }
        .orEmpty()

    private fun persistList(updated: List<SavedConnection>) {
        persist { it.copy(savedConnections = updated) }
        refreshList()
    }

    private fun applyIfChanged(current: List<SavedConnection>, updated: List<SavedConnection>) {
        if (updated != current) persistList(updated)
    }
}
