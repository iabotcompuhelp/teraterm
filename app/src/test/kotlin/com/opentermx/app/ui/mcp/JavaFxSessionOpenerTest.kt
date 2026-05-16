package com.opentermx.app.ui.mcp

import com.opentermx.common.connection.AutoAcceptHostKeyVerifier
import com.opentermx.common.connection.Connection
import com.opentermx.common.connection.ConnectionConfig
import com.opentermx.common.connection.HostKeyVerifier
import com.opentermx.common.connection.SshAuth
import com.opentermx.common.connection.SshConfig
import com.opentermx.common.connection.TcpRawConfig
import com.opentermx.common.connection.TelnetConfig
import com.opentermx.common.credentials.CredentialEntry
import com.opentermx.common.credentials.CredentialKind
import com.opentermx.common.credentials.CredentialStore
import com.opentermx.common.credentials.InMemoryCredentialStore
import com.opentermx.common.crypto.EncryptedValue
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.security.OpenRequest
import com.opentermx.mcp.security.OpenResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests del opener real sin GUI: usa un [FakeSessionLauncher] que NO llama a `connection.connect()`,
 * por lo que ningún socket se abre durante el test.
 */
class JavaFxSessionOpenerTest {

    private class FakeSessionLauncher(
        private val verifier: HostKeyVerifier = AutoAcceptHostKeyVerifier,
        private val sessionId: String = "fake-session",
    ) : SessionLauncher {
        val launched = mutableListOf<Launch>()
        override fun launch(config: ConnectionConfig, name: String, connection: Connection): SessionId {
            launched += Launch(config, name, connection)
            return SessionId(sessionId)
        }

        override fun hostKeyVerifier(): HostKeyVerifier = verifier
    }

    private data class Launch(val config: ConnectionConfig, val name: String, val connection: Connection)

    private fun opener(
        launcher: FakeSessionLauncher = FakeSessionLauncher(),
        store: CredentialStore = CredentialStore.Empty,
    ) = JavaFxSessionOpener(launcher, store) to launcher

    @Test
    fun `SSH sin credentialRef usa password vacía y respeta host port username`() {
        val (op, launcher) = opener()
        val out = op.open(OpenRequest("SSH", "10.0.0.1", 2222, "ops", null, null))
        assertTrue(out is OpenResult.Success)
        val cfg = launcher.launched.single().config as SshConfig
        assertEquals("10.0.0.1", cfg.host)
        assertEquals(2222, cfg.port)
        assertEquals("ops", cfg.username)
        val auth = cfg.auth
        assertTrue(auth is SshAuth.Password && auth.password.isEmpty())
    }

    @Test
    fun `SSH con credentialRef PASSWORD resuelve username y secret desde el keychain`() {
        // Cifrado real con SecretCipher; el opener no lo descifra — lo hace el store via InMemoryCredentialStore.
        val secret = com.opentermx.common.crypto.SecretCipher.encrypt("s3cret")
        val entry = CredentialEntry(
            id = "kc-pass",
            name = "lab admin",
            kind = CredentialKind.PASSWORD,
            username = "labuser",
            secret = secret,
        )
        val store = InMemoryCredentialStore(listOf(entry))
        val (op, launcher) = opener(store = store)
        val out = op.open(OpenRequest("SSH", "10.0.0.1", null, null, "kc-pass", null))
        assertTrue(out is OpenResult.Success)
        val cfg = launcher.launched.single().config as SshConfig
        assertEquals("labuser", cfg.username)
        assertEquals(22, cfg.port)
        val pwd = (cfg.auth as SshAuth.Password).password
        assertEquals("s3cret", String(pwd))
    }

    @Test
    fun `SSH con credentialRef inexistente devuelve Failure`() {
        val (op, launcher) = opener(store = CredentialStore.Empty)
        val out = op.open(OpenRequest("SSH", "1.2.3.4", null, "admin", "missing", null))
        assertTrue(out is OpenResult.Failure)
        assertTrue(launcher.launched.isEmpty())
    }

    @Test
    fun `SSH sin host devuelve Failure`() {
        val (op, _) = opener()
        val out = op.open(OpenRequest("SSH", null, null, "admin", null, null))
        assertTrue(out is OpenResult.Failure)
    }

    @Test
    fun `TELNET ignora credentialRef y arma TelnetConfig`() {
        val (op, launcher) = opener()
        val out = op.open(OpenRequest("TELNET", "1.2.3.4", 2300, null, "ignored", "my-lab"))
        assertTrue(out is OpenResult.Success)
        val cfg = launcher.launched.single().config as TelnetConfig
        assertEquals("1.2.3.4", cfg.host)
        assertEquals(2300, cfg.port)
        assertEquals("my-lab", launcher.launched.single().name)
    }

    @Test
    fun `RAWTCP requiere host y port — sin port devuelve Failure`() {
        val (op, _) = opener()
        val out = op.open(OpenRequest("RAWTCP", "1.2.3.4", null, null, null, null))
        assertTrue(out is OpenResult.Failure)
    }

    @Test
    fun `RAWTCP feliz arma TcpRawConfig`() {
        val (op, launcher) = opener()
        val out = op.open(OpenRequest("RAWTCP", "1.2.3.4", 7000, null, null, null))
        assertTrue(out is OpenResult.Success)
        val cfg = launcher.launched.single().config as TcpRawConfig
        assertEquals("1.2.3.4", cfg.host)
        assertEquals(7000, cfg.port)
    }

    @Test
    fun `protocolo desconocido devuelve Failure`() {
        val (op, _) = opener()
        val out = op.open(OpenRequest("HTTP", "x", null, null, null, null))
        assertTrue(out is OpenResult.Failure)
    }

    @Test
    fun `SSH key con keyPath y passphrase descifrada se mapea a SshAuth_PublicKey`() {
        val passphrase = com.opentermx.common.crypto.SecretCipher.encrypt("pp")
        val entry = CredentialEntry(
            id = "kc-key",
            name = "ops key",
            kind = CredentialKind.SSH_KEY,
            username = "ops",
            keyPath = "/tmp/id_rsa",
            passphrase = passphrase,
        )
        val store = InMemoryCredentialStore(listOf(entry))
        val (op, launcher) = opener(store = store)
        val out = op.open(OpenRequest("SSH", "1.2.3.4", null, null, "kc-key", null))
        assertTrue(out is OpenResult.Success)
        val cfg = launcher.launched.single().config as SshConfig
        val pk = cfg.auth as SshAuth.PublicKey
        assertEquals("/tmp/id_rsa", pk.privateKeyPath)
        assertEquals("pp", String(pk.passphrase!!))
    }

    @Test
    fun `CredentialStore Empty resolveSshAuth devuelve null`() {
        val resolved = CredentialStore.Empty.resolveSshAuth("anything")
        assertNotNull(CredentialStore.Empty) // sanity
        assertSame(null, resolved)
    }

    @Test
    fun `InMemoryCredentialStore con secret EMPTY devuelve null`() {
        val entry = CredentialEntry(
            id = "kc-empty",
            name = "broken",
            kind = CredentialKind.PASSWORD,
            username = "u",
            secret = EncryptedValue.EMPTY,
        )
        val store = InMemoryCredentialStore(listOf(entry))
        assertSame(null, store.resolveSshAuth("kc-empty"))
    }
}