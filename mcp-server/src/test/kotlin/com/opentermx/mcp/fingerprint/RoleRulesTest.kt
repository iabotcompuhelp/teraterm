package com.opentermx.mcp.fingerprint

import com.opentermx.fingerprint.Confidence
import com.opentermx.fingerprint.DeviceIdentity
import com.opentermx.netparsers.Vendor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RoleRulesTest {

    private val rules = RoleRules.embedded()

    private fun identity(vendor: Vendor, model: String?) = DeviceIdentity(
        vendor = vendor,
        model = model,
        osVersion = null,
        hostname = null,
        uptimeText = null,
        confidence = Confidence.HIGH,
    )

    @Test
    fun `fortinet es firewall por vendor, sin importar el modelo`() {
        val inference = rules.infer(identity(Vendor.FORTINET, null))
        assertEquals("firewall", inference.role)
        assertEquals("vendor", inference.matchedBy)
    }

    @Test
    fun `los modelos de los fixtures de 5A se infieren correctamente`() {
        val cases = mapOf(
            identity(Vendor.CISCO_IOS, "WS-C2960X-48TS-L") to "switch",
            identity(Vendor.CISCO_IOSXE, "C9300-48P") to "switch",
            identity(Vendor.CISCO_NXOS, "Nexus9000 C9396PX") to "switch",
            identity(Vendor.HUAWEI_VRP, "CE6850-48S6Q-HI") to "switch",
            identity(Vendor.HUAWEI_VRP, "S5720-28X-SI-AC") to "switch",
            identity(Vendor.ARUBA_AOSCX, "JL658A 6300M 24SFP+ 4SFP56 Swch") to "switch",
            identity(Vendor.MIKROTIK, "CCR1036-12G-4S") to "router",
            identity(Vendor.MIKROTIK, "RB4011iGS+") to "router",
            identity(Vendor.CISCO_IOSXE, "C9800-40-K9") to "wireless_controller",
            identity(Vendor.CISCO_IOS, "AIR-AP2802I-A-K9") to "access_point",
            identity(Vendor.CISCO_IOSXE, "ISR4331/K9") to "router",
        )
        cases.forEach { (id, expected) ->
            assertEquals(expected, rules.infer(id).role, "modelo ${id.model}")
        }
    }

    @Test
    fun `sin modelo ni vendor conocido cae al fallback unknown`() {
        val inference = rules.infer(identity(Vendor.UNKNOWN, null))
        assertEquals("unknown", inference.role)
        assertEquals("fallback", inference.matchedBy)
    }

    @Test
    fun `una regla con modelPattern no matchea identidad sin modelo`() {
        // CISCO_IOS sin modelo: ninguna regla de modelo aplica => fallback.
        assertEquals("unknown", rules.infer(identity(Vendor.CISCO_IOS, null)).role)
    }

    @Test
    fun `version de yaml no soportada o yaml invalido lanzan para que el caller degrade`() {
        assertThrows(IllegalArgumentException::class.java) {
            RoleRules.fromYaml("version: 99\nrules:\n  - role: x\n    when: {}\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RoleRules.fromYaml("version: 1\nrules: []\n")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RoleRules.fromYaml("version: 1\nrules:\n  - role: x\n    when: { vendor: NO_EXISTE }\n")
        }
    }
}
