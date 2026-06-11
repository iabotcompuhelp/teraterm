package com.opentermx.fingerprint.probes

import com.opentermx.fingerprint.Confidence
import com.opentermx.fingerprint.DeviceIdentity
import com.opentermx.fingerprint.FingerprintProbe
import com.opentermx.netparsers.Vendor

/**
 * `display version` de Comware 7 (HPE FlexNetwork / H3C, Fase 6A). Mismo comando que la
 * sonda Huawei pero banners distintos: Comware dice "Comware Software, Version ...",
 * jamás "Huawei Versatile Routing Platform" — por eso ambas sondas conviven sin pisarse.
 *
 * El serial NO está en `display version` (vive en `display device manuinfo`); queda
 * vacío. En IRF (stacking) el output lista un bloque "Slot N" por miembro — se cuenta
 * en extras para que el perfil refleje el stack (error #35).
 */
class ComwareDisplayVersionProbe : FingerprintProbe {

    override val id = "comware_display_version"
    // Después de Aruba: la cadena a ciegas (máx 3) prioriza los vendors con más base
    // instalada; un Comware con vendor detectado va directo por forVendor igual.
    override val order = 35
    override val vendor = Vendor.HPE_COMWARE
    override val command = "display version"

    override fun matches(output: String): Boolean =
        output.contains("Comware Software", ignoreCase = true) ||
            output.contains("Comware Platform Software", ignoreCase = true)

    override fun extract(output: String): DeviceIdentity? {
        if (!matches(output)) return null
        val osVersion = VERSION_LINE.find(output)?.let {
            it.groupValues[1] + " Release " + it.groupValues[2]
        }
        val uptimeMatch = MODEL_UPTIME.find(output)
        val model = uptimeMatch?.groupValues?.get(1)?.trim()
        val uptime = uptimeMatch?.groupValues?.get(2)?.trim()

        val slots = SLOT_HEADER.findAll(output).count()
        val extras = if (slots > 1) mapOf("irfMembers" to slots.toString()) else emptyMap()

        return DeviceIdentity(
            vendor = Vendor.HPE_COMWARE,
            model = model,
            osVersion = osVersion,
            hostname = null,
            uptimeText = uptime,
            confidence = when {
                model != null && osVersion != null -> Confidence.HIGH
                model != null || osVersion != null -> Confidence.MEDIUM
                else -> Confidence.LOW
            },
            extras = extras,
        )
    }

    private companion object {
        // "Comware Software, Version 7.1.070, Release 3208P03"
        val VERSION_LINE = Regex("""(?im)^Comware Software,\s*Version\s+(\S+?),\s*Release\s+(\S+)""")

        // "HPE 5130-24G-4SFP+ EI Switch uptime is 25 weeks, 2 days, 1 hour, 5 minutes"
        // "H3C S5130S-28P-EI uptime is ..."
        val MODEL_UPTIME = Regex(
            """(?im)^(?:HPE|H3C)\s+(.+?)\s+(?:Switch\s+|Router\s+)?uptime is\s+(.+)$"""
        )

        // Un bloque "Slot N:" por miembro del IRF.
        val SLOT_HEADER = Regex("""(?im)^Slot\s+\d+:\s*$""")
    }
}
