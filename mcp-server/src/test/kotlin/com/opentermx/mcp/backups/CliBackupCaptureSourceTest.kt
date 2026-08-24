package com.opentermx.mcp.backups

import com.opentermx.ai.context.Vendor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CliBackupCaptureSourceTest {
    @Test
    fun `elige comandos de captura por familia`() {
        assertEquals("show running-config", CliBackupCaptureSource.captureCommand(Vendor.CISCO_IOS))
        assertEquals("display current-configuration", CliBackupCaptureSource.captureCommand(Vendor.HPE_COMWARE))
        assertEquals("export hide-sensitive", CliBackupCaptureSource.captureCommand(Vendor.MIKROTIK_ROUTEROS))
        assertNull(CliBackupCaptureSource.captureCommand(Vendor.UNKNOWN))
    }

    @Test
    fun `mapea device types sin asumir vendor desconocido`() {
        assertEquals(Vendor.CISCO_IOS_XE, CliBackupCaptureSource.vendorOf("cisco_iosxe"))
        assertEquals(Vendor.FORTINET_FORTIOS, CliBackupCaptureSource.vendorOf("fortinet"))
        assertEquals(Vendor.UNKNOWN, CliBackupCaptureSource.vendorOf("vendor_nuevo"))
    }
}
