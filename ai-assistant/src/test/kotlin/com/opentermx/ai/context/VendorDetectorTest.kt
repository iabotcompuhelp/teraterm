package com.opentermx.ai.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VendorDetectorTest {

    @Test
    fun detectsCiscoIos() {
        val output = """
            Cisco IOS Software, C2960 Software (C2960-LANBASEK9-M), Version 15.0(2)SE11
            ROM: Bootstrap program is C2960 boot loader
        """.trimIndent()
        assertEquals(Vendor.CISCO_IOS, VendorDetector.detect(output))
    }

    @Test
    fun detectsCiscoIosXe() {
        val output = """
            Cisco IOS XE Software, Version 16.09.05
            Cisco IOS Software [Fuji], Catalyst L3 Switch Software (CAT9K_IOSXE)
        """.trimIndent()
        assertEquals(Vendor.CISCO_IOS_XE, VendorDetector.detect(output))
    }

    @Test
    fun detectsCiscoNxOs() {
        val output = """
            Cisco Nexus Operating System (NX-OS) Software
            TAC support: http://www.cisco.com/tac
            Software
              BIOS:      version 07.69
              NXOS: version 9.3(8)
        """.trimIndent()
        assertEquals(Vendor.CISCO_NX_OS, VendorDetector.detect(output))
    }

    @Test
    fun detectsJuniper() {
        val output = "Hostname: srx-edge\nModel: srx320\nJunos OS: 21.4R3-S2.4\nJUNOS Base OS boot"
        assertEquals(Vendor.JUNIPER_JUNOS, VendorDetector.detect(output))
    }

    @Test
    fun detectsHuawei() {
        val output = "Huawei Versatile Routing Platform Software\nVRP (R) software, Version 5.170 (S5720 V200R019C10SPC500)"
        assertEquals(Vendor.HUAWEI_VRP, VendorDetector.detect(output))
    }

    @Test
    fun detectsMikrotik() {
        val output = "MikroTik RouterOS 7.10.2 (stable) running on RB4011iGS+"
        assertEquals(Vendor.MIKROTIK_ROUTEROS, VendorDetector.detect(output))
    }

    @Test
    fun detectsFortinet() {
        val output = "FortiGate-60E # get system status\nVersion: FortiGate-60E v7.0.5,build0304,220404 (GA)"
        assertEquals(Vendor.FORTINET_FORTIOS, VendorDetector.detect(output))
    }

    @Test
    fun unknownVendorWhenNoMatch() {
        assertEquals(Vendor.UNKNOWN, VendorDetector.detect("login: \npassword:"))
        assertEquals(Vendor.UNKNOWN, VendorDetector.detect(""))
    }
}
