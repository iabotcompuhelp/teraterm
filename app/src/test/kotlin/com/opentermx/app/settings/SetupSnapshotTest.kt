package com.opentermx.app.settings

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.opentermx.common.connection.PortForward
import com.opentermx.common.connection.SerialConfig
import com.opentermx.common.connection.SshAuth
import com.opentermx.common.connection.SshConfig
import com.opentermx.common.connection.TcpRawConfig
import com.opentermx.common.connection.TelnetConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SetupSnapshotTest {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val baseSettings = AppSettings()

    private fun roundtrip(snap: SetupSnapshot): SetupSnapshot {
        val json = mapper.writeValueAsString(snap)
        return mapper.readValue(json, SetupSnapshot::class.java)
    }

    @Test
    fun `SSH session roundtrip preserves fields and redacts password`() {
        val original = SshConfig(
            host = "example.com",
            username = "alice",
            auth = SshAuth.Password(charArrayOf('s', 'e', 'c', 'r', 'e', 't')),
            port = 2222,
            keepAliveSeconds = 90,
            agentForwarding = true,
            tryAgentFirst = true,
            portForwards = listOf(
                PortForward(PortForward.Direction.LOCAL, "127.0.0.1", 8080, "internal", 80),
                PortForward(PortForward.Direction.REMOTE, "", 9000, "localhost", 9001),
            ),
            terminalType = "xterm-256color",
            autoLoginMacroPath = "/macros/login.groovy",
        )
        val restored = roundtrip(SnapshotConverters.build(baseSettings, original))
        val cfg = SnapshotConverters.configFromSession(restored.savedSession!!, restored.settings) as SshConfig

        assertEquals("example.com", cfg.host)
        assertEquals(2222, cfg.port)
        assertEquals("alice", cfg.username)
        assertEquals(90, cfg.keepAliveSeconds)
        assertTrue(cfg.agentForwarding)
        assertTrue(cfg.tryAgentFirst)
        assertEquals(2, cfg.portForwards.size)
        assertEquals(PortForward.Direction.LOCAL, cfg.portForwards[0].direction)
        assertEquals(8080, cfg.portForwards[0].bindPort)
        assertEquals("/macros/login.groovy", cfg.autoLoginMacroPath)

        val pwd = cfg.auth as SshAuth.Password
        assertEquals(0, pwd.password.size, "password must be redacted to empty CharArray")
    }

    @Test
    fun `SSH PublicKey roundtrip nulls passphrase`() {
        val original = SshConfig(
            host = "h",
            username = "u",
            auth = SshAuth.PublicKey("/keys/id_ed25519", charArrayOf('p', 'a', 's', 's')),
        )
        val restored = roundtrip(SnapshotConverters.build(baseSettings, original))
        val cfg = SnapshotConverters.configFromSession(restored.savedSession!!, restored.settings) as SshConfig

        val pk = cfg.auth as SshAuth.PublicKey
        assertEquals("/keys/id_ed25519", pk.privateKeyPath)
        assertNull(pk.passphrase, "passphrase must be redacted to null")
    }

    @Test
    fun `Telnet session roundtrip preserves fields`() {
        val original = TelnetConfig(
            host = "telnet.example",
            port = 2323,
            useTls = true,
            keepAlive = false,
            recvBufferSize = 4096,
            dnsMode = "IPV6",
            autoLoginMacroPath = "/m/t.groovy",
        )
        val restored = roundtrip(SnapshotConverters.build(baseSettings, original))
        val cfg = SnapshotConverters.configFromSession(restored.savedSession!!, restored.settings) as TelnetConfig

        assertEquals("telnet.example", cfg.host)
        assertEquals(2323, cfg.port)
        assertTrue(cfg.useTls)
        assertFalse(cfg.keepAlive)
        assertEquals(4096, cfg.recvBufferSize)
        assertEquals("IPV6", cfg.dnsMode)
        assertEquals("/m/t.groovy", cfg.autoLoginMacroPath)
    }

    @Test
    fun `TcpRaw session roundtrip preserves fields`() {
        val original = TcpRawConfig(
            host = "raw.example",
            port = 9999,
            keepAlive = false,
            recvBufferSize = 2048,
            dnsMode = "IPV4",
        )
        val restored = roundtrip(SnapshotConverters.build(baseSettings, original))
        val cfg = SnapshotConverters.configFromSession(restored.savedSession!!, restored.settings) as TcpRawConfig

        assertEquals("raw.example", cfg.host)
        assertEquals(9999, cfg.port)
        assertFalse(cfg.keepAlive)
        assertEquals(2048, cfg.recvBufferSize)
        assertEquals("IPV4", cfg.dnsMode)
    }

    @Test
    fun `Serial session roundtrip preserves enum names`() {
        val original = SerialConfig(
            portName = "COM5",
            baudRate = 115200,
            dataBits = 7,
            stopBits = SerialConfig.StopBits.TWO,
            parity = SerialConfig.Parity.EVEN,
            flowControl = SerialConfig.FlowControl.RTS_CTS,
            autoLoginMacroPath = "/m/s.groovy",
        )
        val restored = roundtrip(SnapshotConverters.build(baseSettings, original))
        val cfg = SnapshotConverters.configFromSession(restored.savedSession!!, restored.settings) as SerialConfig

        assertEquals("COM5", cfg.portName)
        assertEquals(115200, cfg.baudRate)
        assertEquals(7, cfg.dataBits)
        assertEquals(SerialConfig.StopBits.TWO, cfg.stopBits)
        assertEquals(SerialConfig.Parity.EVEN, cfg.parity)
        assertEquals(SerialConfig.FlowControl.RTS_CTS, cfg.flowControl)
        assertEquals("/m/s.groovy", cfg.autoLoginMacroPath)
    }

    @Test
    fun `importSnapshot wraps legacy AppSettings file`(@TempDir tmp: Path) {
        val legacyFile = tmp.resolve("legacy.json").toFile()
        mapper.writerWithDefaultPrettyPrinter()
            .writeValue(legacyFile, AppSettings(theme = "LIGHT", locale = "en"))

        val snap = SettingsStore.importSnapshot(legacyFile)

        assertNull(snap.savedSession)
        assertEquals("LIGHT", snap.settings.theme)
        assertEquals("en", snap.settings.locale)
    }

    @Test
    fun `importSnapshot reads new SetupSnapshot file`(@TempDir tmp: Path) {
        val original = SetupSnapshot(
            settings = AppSettings(theme = "DARK"),
            savedSession = SnapshotConverters.sessionFromConfig(
                TelnetConfig(host = "h", port = 23)
            ),
        )
        val target = tmp.resolve("snap.json").toFile()
        SettingsStore.exportSnapshot(original, target)

        val read = SettingsStore.importSnapshot(target)

        assertEquals("DARK", read.settings.theme)
        assertNotNull(read.savedSession)
        assertEquals("TELNET", read.savedSession!!.type)
        assertEquals("h", read.savedSession!!.telnet!!.host)
    }
}