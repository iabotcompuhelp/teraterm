package com.opentermx.fingerprint.probes

import com.opentermx.fingerprint.Confidence
import com.opentermx.fingerprint.DeviceIdentity
import com.opentermx.fingerprint.FingerprintProbe
import com.opentermx.netparsers.Vendor

/**
 * `get system status` de FortiOS. Es la sonda que más campos da en un solo comando:
 * modelo, versión, serial, hostname y modo HA. El rol HA se normaliza a
 * active/standby/standalone (vocabulario del modelo, no del vendor).
 */
class FortinetGetStatusProbe : FingerprintProbe {

    override val id = "fortinet_get_status"
    override val order = 40
    override val vendor = Vendor.FORTINET
    override val command = "get system status"

    override fun matches(output: String): Boolean = VERSION_LINE.containsMatchIn(output)

    override fun extract(output: String): DeviceIdentity? {
        val version = VERSION_LINE.find(output) ?: return null
        val model = version.groupValues[1]
        val osVersion = version.groupValues[2] + " build" + version.groupValues[3]
        val serial = SERIAL.find(output)?.groupValues?.get(1)
        val hostname = HOSTNAME.find(output)?.groupValues?.get(1)
        val uptime = UPTIME.find(output)?.groupValues?.get(1)?.trim()
        val haRole = HA_MODE.find(output)?.groupValues?.get(1)?.let { normalizeHa(it) }
        return DeviceIdentity(
            vendor = Vendor.FORTINET,
            model = model,
            osVersion = osVersion,
            serialNumbers = serial?.let { listOf(it) } ?: emptyList(),
            hostname = hostname,
            uptimeText = uptime,
            haRole = haRole,
            confidence = Confidence.HIGH, // la línea Version trae modelo + versión o no matchea
        )
    }

    private fun normalizeHa(raw: String): String {
        val lower = raw.lowercase()
        return when {
            "standalone" in lower -> "standalone"
            "master" in lower || "primary" in lower -> "active"
            "slave" in lower || "secondary" in lower || "backup" in lower -> "standby"
            else -> raw.trim()
        }
    }

    private companion object {
        // "Version: FortiGate-100F v7.2.5,build1517,230620 (GA.F)"
        val VERSION_LINE = Regex("""(?im)^Version:\s*(Forti\S+)\s+v([\d.]+),build(\d+)""")
        val SERIAL = Regex("""(?im)^Serial-Number:\s*(\S+)""")
        val HOSTNAME = Regex("""(?im)^Hostname:\s*(\S+)""")
        val UPTIME = Regex("""(?im)^Uptime:\s*(.+)$""")
        val HA_MODE = Regex("""(?im)^Current HA mode:\s*(.+)$""")
    }
}
