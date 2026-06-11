package com.opentermx.fingerprint.probes

import com.opentermx.fingerprint.Confidence
import com.opentermx.fingerprint.DeviceIdentity
import com.opentermx.fingerprint.FingerprintProbe
import com.opentermx.netparsers.Vendor

/**
 * `show system` de AOS-CX: formato clave-valor con dos puntos alineados. El "Product
 * Name" trae modelo + descripción comercial ("JL658A 6300M 24SFP+ ...") — se guarda
 * completo, las reglas de rol matchean por substring.
 */
class ArubaCxShowSystemProbe : FingerprintProbe {

    override val id = "arubacx_show_system"
    override val order = 30
    override val vendor = Vendor.ARUBA_AOSCX
    override val command = "show system"

    override fun matches(output: String): Boolean = output.contains("ArubaOS-CX")

    override fun extract(output: String): DeviceIdentity? {
        if (!matches(output)) return null
        val hostname = field(output, "Hostname")?.takeWhile { !it.isWhitespace() }
        val model = field(output, "Product Name")
        val osVersion = field(output, "ArubaOS-CX Version")?.takeWhile { !it.isWhitespace() }
        val serial = field(output, "Chassis Serial Nbr")?.takeWhile { !it.isWhitespace() }
        val uptime = field(output, "Up Time")
        return DeviceIdentity(
            vendor = Vendor.ARUBA_AOSCX,
            model = model,
            osVersion = osVersion,
            serialNumbers = serial?.let { listOf(it) } ?: emptyList(),
            hostname = hostname,
            uptimeText = uptime,
            confidence = when {
                model != null && osVersion != null -> Confidence.HIGH
                model != null || osVersion != null -> Confidence.MEDIUM
                else -> Confidence.LOW
            },
        )
    }

    /** Valor de una línea `Clave : valor` (clave exacta, valor hasta fin de línea). */
    private fun field(output: String, key: String): String? =
        Regex("""(?im)^\s*${Regex.escape(key)}\s*:\s*(.+)$""")
            .find(output)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
}
