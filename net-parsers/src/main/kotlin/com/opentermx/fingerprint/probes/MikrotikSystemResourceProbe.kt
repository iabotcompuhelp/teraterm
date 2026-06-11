package com.opentermx.fingerprint.probes

import com.opentermx.fingerprint.Confidence
import com.opentermx.fingerprint.DeviceIdentity
import com.opentermx.fingerprint.FingerprintProbe
import com.opentermx.netparsers.Vendor

/**
 * `/system resource print` de RouterOS (formato `clave: valor` con indentación variable).
 * No trae serial: el [secondaryCommand] `/system routerboard print` lo agrega vía
 * [enrich] (y el modelo exacto en equipos donde `board-name` difiere del SKU).
 * El hostname de RouterOS vive en `/system identity` — fuera del alcance de esta sonda.
 */
class MikrotikSystemResourceProbe : FingerprintProbe {

    override val id = "mikrotik_system_resource"
    override val order = 50
    override val vendor = Vendor.MIKROTIK
    override val command = "/system resource print"
    override val secondaryCommand = "/system routerboard print"

    override fun matches(output: String): Boolean =
        PLATFORM.containsMatchIn(output) ||
            (field(output, "board-name") != null && field(output, "version") != null)

    override fun extract(output: String): DeviceIdentity? {
        if (!matches(output)) return null
        val model = field(output, "board-name")
        val osVersion = field(output, "version")
        return DeviceIdentity(
            vendor = Vendor.MIKROTIK,
            model = model,
            osVersion = osVersion,
            hostname = null,
            uptimeText = field(output, "uptime"),
            confidence = when {
                model != null && osVersion != null -> Confidence.HIGH
                model != null || osVersion != null -> Confidence.MEDIUM
                else -> Confidence.LOW
            },
        )
    }

    override fun enrich(identity: DeviceIdentity, secondaryOutput: String): DeviceIdentity {
        val serial = field(secondaryOutput, "serial-number")
        val model = field(secondaryOutput, "model")
        if (serial == null && model == null) return identity
        return identity.copy(
            model = identity.model ?: model,
            serialNumbers = serial?.let { listOf(it) } ?: identity.serialNumbers,
        )
    }

    private fun field(output: String, key: String): String? =
        Regex("""(?im)^\s*${Regex.escape(key)}:\s*(.+)$""")
            .find(output)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

    private companion object {
        val PLATFORM = Regex("""(?im)^\s*platform:\s*MikroTik\s*$""")
    }
}
