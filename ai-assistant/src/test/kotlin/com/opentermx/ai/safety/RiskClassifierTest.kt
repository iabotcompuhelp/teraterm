package com.opentermx.ai.safety

import com.opentermx.ai.context.Vendor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RiskClassifierTest {

    @Test
    fun safeShowCommandsForCisco() {
        val classified = RiskClassifier.classifyLine("show running-config", Vendor.CISCO_IOS)
        assertEquals(RiskLevel.SAFE, classified.risk)
        assertEquals(RiskLevel.SAFE, RiskClassifier.classifyLine("ping 8.8.8.8", Vendor.CISCO_IOS).risk)
    }

    @Test
    fun configEntersConfigMode() {
        assertEquals(RiskLevel.CONFIG, RiskClassifier.classifyLine("configure terminal", Vendor.CISCO_IOS).risk)
        assertEquals(RiskLevel.CONFIG, RiskClassifier.classifyLine("interface GigabitEthernet0/1", Vendor.CISCO_IOS).risk)
        assertEquals(RiskLevel.CONFIG, RiskClassifier.classifyLine(" switchport access vlan 30", Vendor.CISCO_IOS).risk)
    }

    @Test
    fun dangerousCommandsAreFlagged() {
        assertEquals(RiskLevel.DANGEROUS, RiskClassifier.classifyLine("erase startup-config", Vendor.CISCO_IOS).risk)
        assertEquals(RiskLevel.DANGEROUS, RiskClassifier.classifyLine("reload", Vendor.CISCO_IOS).risk)
        assertEquals(RiskLevel.DANGEROUS, RiskClassifier.classifyLine("write erase", Vendor.CISCO_IOS).risk)
        assertEquals(RiskLevel.DANGEROUS, RiskClassifier.classifyLine("shutdown", Vendor.CISCO_IOS).risk)
    }

    @Test
    fun juniperDeleteIsDangerous() {
        assertEquals(RiskLevel.DANGEROUS, RiskClassifier.classifyLine("delete interfaces ge-0/0/0", Vendor.JUNIPER_JUNOS).risk)
        assertEquals(RiskLevel.SAFE, RiskClassifier.classifyLine("show interfaces", Vendor.JUNIPER_JUNOS).risk)
    }

    @Test
    fun classifiesWholeBlock() {
        val block = listOf(
            "configure terminal",
            "interface GigabitEthernet0/1",
            " ip address 10.0.0.1 255.255.255.0",
            " no shutdown",
            "end",
            "write memory",
            "show ip interface brief",
        )
        val result = RiskClassifier.classify(block, Vendor.CISCO_IOS)
        assertEquals(RiskLevel.CONFIG, result[0].risk)
        assertEquals(RiskLevel.CONFIG, result[1].risk)
        assertEquals(RiskLevel.CONFIG, result[2].risk)
        assertEquals(RiskLevel.CONFIG, result[3].risk)
        assertEquals(RiskLevel.CONFIG, result[4].risk)
        assertEquals(RiskLevel.CONFIG, result[5].risk)
        assertEquals(RiskLevel.SAFE, result[6].risk)
    }
}
