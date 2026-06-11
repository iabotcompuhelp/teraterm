package com.opentermx.fingerprint.probes

import com.opentermx.fingerprint.Confidence
import com.opentermx.fingerprint.DeviceIdentity
import com.opentermx.fingerprint.FingerprintProbe
import com.opentermx.netparsers.Vendor

/**
 * `display version` de Huawei VRP (V5 y V8). El output no trae hostname ni serial —
 * quedan null/vacío (regla de oro: no se inventa).
 */
class HuaweiDisplayVersionProbe : FingerprintProbe {

    override val id = "huawei_display_version"
    override val order = 20
    override val vendor = Vendor.HUAWEI_VRP
    override val command = "display version"

    override fun matches(output: String): Boolean =
        output.contains("Huawei Versatile Routing Platform") ||
            output.contains("VRP (R) software", ignoreCase = true)

    override fun extract(output: String): DeviceIdentity? {
        if (!matches(output)) return null
        // "VRP (R) software, Version 8.180 (CE6850EI V200R005C10SPC800)"
        val osVersion = VRP_VERSION.find(output)?.groupValues?.get(1)?.trim()
        // "HUAWEI CE6850-48S6Q-HI uptime is 122 days, 3 hours, 21 minutes"
        // "Huawei S5720-28X-SI-AC Routing Switch uptime is 45 weeks, 2 days"
        val uptimeMatch = MODEL_UPTIME.find(output)
        val model = uptimeMatch?.groupValues?.get(1)
        val uptime = uptimeMatch?.groupValues?.get(2)?.trim()
        return DeviceIdentity(
            vendor = Vendor.HUAWEI_VRP,
            model = model,
            osVersion = osVersion,
            hostname = null,
            uptimeText = uptime,
            confidence = when {
                model != null && osVersion != null -> Confidence.HIGH
                model != null || osVersion != null -> Confidence.MEDIUM
                else -> Confidence.LOW
            },
        )
    }

    private companion object {
        val VRP_VERSION = Regex("""(?im)VRP \(R\) software,\s*Version\s+(.+)$""")
        val MODEL_UPTIME = Regex("""(?im)^(?:HUAWEI|Huawei|Quidway)\s+(\S+)\b.*?\buptime is\s+(.+)$""")
    }
}
