package com.opentermx.app.ui.mcp

import com.opentermx.common.connection.Connection
import com.opentermx.common.connection.ConnectionConfig
import com.opentermx.common.connection.SerialConfig
import com.opentermx.common.connection.SshAuth
import com.opentermx.common.connection.SshConfig
import com.opentermx.common.connection.TcpRawConfig
import com.opentermx.common.connection.TelnetConfig
import com.opentermx.common.credentials.CredentialStore
import com.opentermx.mcp.security.OpenRequest
import com.opentermx.mcp.security.OpenResult
import com.opentermx.mcp.security.SessionOpener
import com.opentermx.serial.SerialConnectionFactory
import com.opentermx.ssh.SshConnection
import com.opentermx.telnet.RawTcpConnection
import com.opentermx.telnet.TelnetConnection

/**
 * Implementación productiva de [SessionOpener]. Construye la `ConnectionConfig` adecuada para
 * cada protocolo, resuelve `credentialRef` contra el [CredentialStore] (solo aplica a SSH) y
 * delega la apertura efectiva en el [SessionLauncher], que coordina con el FX thread.
 *
 * Decisiones:
 *  - SSH sin `credentialRef` ⇒ `SshAuth.Password(empty)`; útil cuando hay auth por agente o
 *    cuando el usuario va a aceptar el prompt interactivo en el host. Si el operador requiere
 *    auth fuerte, debe crear una `CredentialEntry` y referenciarla por id.
 *  - TELNET / RAWTCP / SERIAL ignoran `credentialRef` (no aplica) y `username` es opcional.
 *  - El backend serial usa el resolver default (`SerialConnectionFactory.defaultBackend()`).
 */
class JavaFxSessionOpener(
    private val launcher: SessionLauncher,
    private val credentialStore: CredentialStore,
    private val serialBackendResolver: () -> SerialConnectionFactory.Backend = { SerialConnectionFactory.Backend.fromSystemProperty() },
) : SessionOpener {

    override fun open(request: OpenRequest): OpenResult {
        val protocol = request.protocol.uppercase()
        return try {
            when (protocol) {
                "SSH" -> openSsh(request)
                "TELNET" -> openTelnet(request)
                "RAWTCP", "TCP_RAW", "RAW_TCP" -> openTcpRaw(request)
                "SERIAL" -> openSerial(request)
                else -> OpenResult.Failure("protocolo no soportado: ${request.protocol}")
            }
        } catch (t: Throwable) {
            OpenResult.Failure("error abriendo sesión: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun openSsh(request: OpenRequest): OpenResult {
        val host = request.host?.takeIf { it.isNotBlank() }
            ?: return OpenResult.Failure("SSH requiere 'host'")
        val port = request.port ?: 22
        val auth = resolveSshAuth(request)
            ?: return OpenResult.Failure("credentialRef inválido o no resuelve a credencial usable")
        val username = request.username?.takeIf { it.isNotBlank() }
            ?: usernameFromCredential(request.credentialRef)
            ?: return OpenResult.Failure("SSH requiere 'username' (directo o vía credentialRef)")
        val config = SshConfig(host = host, username = username, auth = auth, port = port)
        val name = request.label?.takeIf { it.isNotBlank() } ?: "$username@$host:$port"
        val connection = SshConnection(config, launcher.hostKeyVerifier())
        return launchAndWrap(config, name, connection)
    }

    private fun openTelnet(request: OpenRequest): OpenResult {
        val host = request.host?.takeIf { it.isNotBlank() }
            ?: return OpenResult.Failure("TELNET requiere 'host'")
        val port = request.port ?: 23
        val config = TelnetConfig(host = host, port = port)
        val name = request.label?.takeIf { it.isNotBlank() } ?: "telnet://$host:$port"
        return launchAndWrap(config, name, TelnetConnection(config))
    }

    private fun openTcpRaw(request: OpenRequest): OpenResult {
        val host = request.host?.takeIf { it.isNotBlank() }
            ?: return OpenResult.Failure("RAWTCP requiere 'host'")
        val port = request.port ?: return OpenResult.Failure("RAWTCP requiere 'port'")
        val config = TcpRawConfig(host = host, port = port)
        val name = request.label?.takeIf { it.isNotBlank() } ?: "$host:$port"
        return launchAndWrap(config, name, RawTcpConnection(config))
    }

    private fun openSerial(request: OpenRequest): OpenResult {
        // Para SERIAL el campo `host` lleva el portName (COM3, /dev/ttyUSB0, etc).
        val portName = request.host?.takeIf { it.isNotBlank() }
            ?: return OpenResult.Failure("SERIAL requiere 'host' con el nombre del puerto (ej. COM3)")
        val config = SerialConfig(portName = portName)
        val name = request.label?.takeIf { it.isNotBlank() } ?: portName
        return launchAndWrap(config, name, SerialConnectionFactory.create(config, serialBackendResolver()))
    }

    private fun launchAndWrap(config: ConnectionConfig, name: String, connection: Connection): OpenResult {
        val sessionId = launcher.launch(config, name, connection)
        return OpenResult.Success(sessionId = sessionId.value, label = name)
    }

    private fun resolveSshAuth(request: OpenRequest): SshAuth? {
        val ref = request.credentialRef?.takeIf { it.isNotBlank() }
            ?: return SshAuth.Password(CharArray(0))
        return credentialStore.resolveSshAuth(ref)
    }

    private fun usernameFromCredential(credentialRef: String?): String? {
        val ref = credentialRef?.takeIf { it.isNotBlank() } ?: return null
        return credentialStore.findById(ref)?.username?.takeIf { it.isNotBlank() }
    }
}