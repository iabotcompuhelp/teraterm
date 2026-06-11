package com.opentermx.ai.context

/**
 * Detecta el vendor del dispositivo conectado a partir del output del terminal
 * (típicamente la respuesta a `show version`, banner SSH o prompt).
 *
 * Los patrones son intencionadamente conservadores — preferimos UNKNOWN sobre
 * un falso positivo, porque el system prompt usa el vendor para elegir sintaxis CLI.
 */
object VendorDetector {

    fun detect(output: String): Vendor {
        if (output.isBlank()) return Vendor.UNKNOWN
        val sample = output.take(4096) // analizar solo la cabeza, el banner suele venir primero

        return when {
            looksLikeCiscoNxOs(sample) -> Vendor.CISCO_NX_OS
            looksLikeCiscoIosXe(sample) -> Vendor.CISCO_IOS_XE
            looksLikeCiscoIos(sample) -> Vendor.CISCO_IOS
            looksLikeJuniper(sample) -> Vendor.JUNIPER_JUNOS
            // Comware ANTES que Huawei: comparten linaje (H3C) y formatos parecidos,
            // pero el banner de Comware nunca dice "Huawei" y viceversa (error #60:
            // la familia decide, no la marca del chasis).
            looksLikeComware(sample) -> Vendor.HPE_COMWARE
            looksLikeHuawei(sample) -> Vendor.HUAWEI_VRP
            looksLikeMikrotik(sample) -> Vendor.MIKROTIK_ROUTEROS
            looksLikeAruba(sample) -> Vendor.ARUBA_OS
            looksLikeFortinet(sample) -> Vendor.FORTINET_FORTIOS
            else -> Vendor.UNKNOWN
        }
    }

    private fun looksLikeCiscoIos(s: String): Boolean =
        s.contains("Cisco IOS Software", ignoreCase = true) ||
            s.contains("Cisco Internetwork Operating System", ignoreCase = true) ||
            Regex("""Cisco IOS .*Version """).containsMatchIn(s)

    private fun looksLikeCiscoIosXe(s: String): Boolean =
        s.contains("IOS-XE", ignoreCase = true) || s.contains("IOS XE", ignoreCase = true)

    private fun looksLikeCiscoNxOs(s: String): Boolean =
        s.contains("Cisco Nexus Operating System", ignoreCase = true) ||
            s.contains("NX-OS", ignoreCase = true)

    private fun looksLikeJuniper(s: String): Boolean =
        s.contains("JUNOS", ignoreCase = true) ||
            s.contains("Junos OS", ignoreCase = true) ||
            s.contains("Juniper Networks", ignoreCase = true)

    private fun looksLikeComware(s: String): Boolean =
        s.contains("Comware", ignoreCase = true) ||
            s.contains("H3C", ignoreCase = false)

    private fun looksLikeHuawei(s: String): Boolean =
        s.contains("Huawei Versatile Routing Platform", ignoreCase = true) ||
            s.contains("HUAWEI", ignoreCase = false) && s.contains("VRP", ignoreCase = false) ||
            s.contains("Huawei Technologies", ignoreCase = true)

    private fun looksLikeMikrotik(s: String): Boolean =
        s.contains("MikroTik", ignoreCase = true) || s.contains("RouterOS", ignoreCase = true)

    private fun looksLikeAruba(s: String): Boolean =
        s.contains("ArubaOS", ignoreCase = true) ||
            s.contains("Aruba Networks", ignoreCase = true) ||
            s.contains("Hewlett Packard Enterprise", ignoreCase = true) && s.contains("Aruba", ignoreCase = true)

    private fun looksLikeFortinet(s: String): Boolean =
        s.contains("FortiOS", ignoreCase = true) ||
            s.contains("FortiGate", ignoreCase = true) ||
            s.contains("Fortinet", ignoreCase = true)
}
