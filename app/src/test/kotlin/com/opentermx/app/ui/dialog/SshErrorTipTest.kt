package com.opentermx.app.ui.dialog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SshErrorTipTest {

    @Test
    fun `kex negotiation fail produce tip KEX`() {
        val msg = "Algorithm negotiation fail: algorithmName=\"kex\" jschProposal=curve25519-sha256 serverProposal=ecdh-sha2-nistp256"
        assertEquals(SshErrorTip.KEX_NEGOTIATION, SshErrorTip.resolve(msg))
    }

    @Test
    fun `cipher negotiation fail produce tip CIPHER`() {
        val msg = "Algorithm negotiation fail: algorithmName=cipher.s2c jschProposal=aes256-ctr serverProposal=aes256-gcm"
        assertEquals(SshErrorTip.CIPHER_NEGOTIATION, SshErrorTip.resolve(msg))
    }

    @Test
    fun `mac negotiation fail produce tip MAC`() {
        val msg = "Algorithm negotiation fail: algorithmName='mac.s2c' jschProposal=hmac-sha2-256-etm@openssh.com serverProposal=hmac-sha1"
        assertEquals(SshErrorTip.MAC_NEGOTIATION, SshErrorTip.resolve(msg))
    }

    @Test
    fun `hostkey negotiation fail produce tip HOSTKEY_NEGOTIATION`() {
        val msg = "Algorithm negotiation fail: algorithmName=server_host_key jschProposal=ssh-ed25519 serverProposal=ssh-rsa"
        assertEquals(SshErrorTip.HOSTKEY_NEGOTIATION, SshErrorTip.resolve(msg))
    }

    @Test
    fun `unknown host key produce tip HOSTKEY_UNKNOWN_OR_CHANGED`() {
        val msg = "com.jcraft.jsch.JSchException: UnknownHostKey: 10.0.0.1. RSA key fingerprint is …"
        assertEquals(SshErrorTip.HOSTKEY_UNKNOWN_OR_CHANGED, SshErrorTip.resolve(msg))
    }

    @Test
    fun `hostkey changed produce tip HOSTKEY_UNKNOWN_OR_CHANGED`() {
        val msg = "HostKey has been changed for 10.0.0.1"
        assertEquals(SshErrorTip.HOSTKEY_UNKNOWN_OR_CHANGED, SshErrorTip.resolve(msg))
    }

    @Test
    fun `mensaje generico devuelve null`() {
        assertNull(SshErrorTip.resolve("Connection refused"))
        assertNull(SshErrorTip.resolve(null))
        assertNull(SshErrorTip.resolve(""))
    }

    @Test
    fun `inspecciona el cause chain cuando el message es generico`() {
        val root = RuntimeException("Algorithm negotiation fail (algorithmName=kex)")
        val wrapper = RuntimeException("Could not connect", root)
        assertEquals(SshErrorTip.KEX_NEGOTIATION, SshErrorTip.resolve(wrapper.message, wrapper))
    }

    @Test
    fun `opensSshGeneral es false solo para hostkey unknown`() {
        assertTrue(SshErrorTip.KEX_NEGOTIATION.opensSshGeneral)
        assertTrue(SshErrorTip.CIPHER_NEGOTIATION.opensSshGeneral)
        assertTrue(SshErrorTip.MAC_NEGOTIATION.opensSshGeneral)
        assertTrue(SshErrorTip.HOSTKEY_NEGOTIATION.opensSshGeneral)
        assertFalse(SshErrorTip.HOSTKEY_UNKNOWN_OR_CHANGED.opensSshGeneral)
    }

    @Test
    fun `fallback heuristico cuando algorithmName no parsea`() {
        // JSch viejo puede no exponer `algorithmName=` literal — caemos a keywords sueltas.
        val msg = "negotiation fail because kex proposal was empty"
        assertEquals(SshErrorTip.KEX_NEGOTIATION, SshErrorTip.resolve(msg))
    }
}
