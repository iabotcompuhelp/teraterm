package com.opentermx.ai.context

enum class Vendor(val displayName: String, val cliFamily: String) {
    CISCO_IOS("Cisco IOS", "cisco-ios"),
    CISCO_IOS_XE("Cisco IOS-XE", "cisco-ios"),
    CISCO_NX_OS("Cisco NX-OS", "cisco-nxos"),
    JUNIPER_JUNOS("Juniper JunOS", "junos"),
    HUAWEI_VRP("Huawei VRP", "huawei-vrp"),
    MIKROTIK_ROUTEROS("MikroTik RouterOS", "mikrotik"),
    ARUBA_OS("Aruba OS", "aruba"),
    FORTINET_FORTIOS("FortiNet FortiOS", "fortios"),
    UNKNOWN("Unknown", "generic");
}
