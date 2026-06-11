package com.opentermx.fingerprint.probes

import com.opentermx.fingerprint.Confidence
import com.opentermx.fingerprint.DeviceIdentity
import com.opentermx.fingerprint.FingerprintProbe
import com.opentermx.netparsers.Vendor

/**
 * `show version` para toda la familia Cisco. Una sola sonda distingue IOS / IOS-XE /
 * NX-OS por el banner — son el mismo comando y conviene gastar UN intento de la cadena,
 * no tres.
 *
 * Stacks (error #35): un `show version` de stack devuelve N "Model Number" y N "System
 * Serial Number". Se guardan TODOS los seriales; el modelo reportado es el del master
 * (primera fila) y, si los demás difieren, van a `extras["stackMembers"]`.
 */
class CiscoShowVersionProbe : FingerprintProbe {

    override val id = "cisco_show_version"
    override val order = 10
    override val vendor = Vendor.CISCO_IOS
    override val command = "show version"

    override fun matches(output: String): Boolean =
        MARKERS.any { output.contains(it, ignoreCase = false) }

    override fun extract(output: String): DeviceIdentity? {
        if (!matches(output)) return null
        val vendor = when {
            output.contains("Nexus Operating System") || output.contains("NX-OS") -> Vendor.CISCO_NXOS
            output.contains("IOS-XE") || output.contains("IOS XE") -> Vendor.CISCO_IOSXE
            else -> Vendor.CISCO_IOS
        }
        return when (vendor) {
            Vendor.CISCO_NXOS -> extractNxos(output)
            else -> extractIos(output, vendor)
        }
    }

    private fun extractIos(output: String, vendor: Vendor): DeviceIdentity {
        val osVersion = IOS_VERSION.find(output)?.groupValues?.get(1)

        // Catalyst stackeable: tabla "Model Number : ..." por miembro.
        val stackModels = STACK_MODEL.findAll(output).map { it.groupValues[1] }.toList()
        val stackSerials = STACK_SERIAL.findAll(output).map { it.groupValues[1] }.toList()

        val model = stackModels.firstOrNull()
            ?: PROCESSOR_LINE.find(output)?.groupValues?.get(1)
        val serials = stackSerials.ifEmpty {
            BOARD_ID.find(output)?.groupValues?.get(1)?.let { listOf(it) } ?: emptyList()
        }

        val uptimeMatch = UPTIME_LINE.find(output)
        val hostname = uptimeMatch?.groupValues?.get(1)
        val uptime = uptimeMatch?.groupValues?.get(2)?.trim()

        val extras = buildMap {
            val rest = stackModels.drop(1)
            if (rest.isNotEmpty() && rest.any { it != stackModels.first() }) {
                put("stackMembers", stackModels.joinToString(","))
            }
        }
        return DeviceIdentity(
            vendor = vendor,
            model = model,
            osVersion = osVersion,
            serialNumbers = serials,
            hostname = hostname,
            uptimeText = uptime,
            confidence = confidenceFor(model, osVersion),
            extras = extras,
        )
    }

    private fun extractNxos(output: String): DeviceIdentity {
        val osVersion = NXOS_VERSION.find(output)?.groupValues?.get(1)
        val model = NXOS_CHASSIS.find(output)?.groupValues?.get(1)?.trim()
        val serial = NXOS_BOARD_ID.find(output)?.groupValues?.get(1)
        val hostname = NXOS_DEVICE_NAME.find(output)?.groupValues?.get(1)
        val uptime = NXOS_UPTIME.find(output)?.groupValues?.get(1)?.trim()
        return DeviceIdentity(
            vendor = Vendor.CISCO_NXOS,
            model = model,
            osVersion = osVersion,
            serialNumbers = serial?.let { listOf(it) } ?: emptyList(),
            hostname = hostname,
            uptimeText = uptime,
            confidence = confidenceFor(model, osVersion),
        )
    }

    private fun confidenceFor(model: String?, version: String?): Confidence = when {
        model != null && version != null -> Confidence.HIGH
        model != null || version != null -> Confidence.MEDIUM
        else -> Confidence.LOW
    }

    private companion object {
        val MARKERS = listOf(
            "Cisco IOS Software",
            "Cisco IOS XE Software",
            "IOS-XE",
            "Cisco Nexus Operating System",
            "NX-OS",
        )

        // "Cisco IOS Software, ... Version 15.2(7)E3, RELEASE..." / "... Version 17.3.4a"
        val IOS_VERSION = Regex("""Version\s+([\w.()\-]+),?""")

        // "cisco WS-C2960X-48TS-L (APM86XXX) processor ..." / "Cisco ISR4331/K9 (1RU) processor ..."
        val PROCESSOR_LINE = Regex("""(?im)^cisco\s+(\S+)\s+\([^)]*\)\s+processor""")

        // Tabla de stack (Catalyst 2960X/3850/9300): "Model Number : C9300-48P"
        val STACK_MODEL = Regex("""(?im)^\s*Model [Nn]umber\s*:\s*(\S+)""")
        val STACK_SERIAL = Regex("""(?im)^\s*System [Ss]erial [Nn]umber\s*:\s*(\S+)""")

        val BOARD_ID = Regex("""(?im)^Processor board ID\s+(\S+)""")

        // "sw-acceso-p2 uptime is 5 weeks, 1 day, 3 hours, 12 minutes"
        val UPTIME_LINE = Regex("""(?im)^(\S+)\s+uptime is\s+(.+)$""")

        // NX-OS: "  NXOS: version 9.3(5)" (n9k) o "  system:    version 7.0(3)I7(4)"
        val NXOS_VERSION = Regex("""(?im)^\s*(?:NXOS|system):\s+version\s+(\S+)""")

        // "  cisco Nexus9000 C9396PX Chassis"
        val NXOS_CHASSIS = Regex("""(?im)^\s*cisco\s+(Nexus\S*\s+\S+)\s+Chassis""")
        val NXOS_BOARD_ID = Regex("""(?im)^\s*Processor Board ID\s+(\S+)""")
        val NXOS_DEVICE_NAME = Regex("""(?im)^\s*Device name:\s+(\S+)""")
        val NXOS_UPTIME = Regex("""(?im)^\s*Kernel uptime is\s+(.+)$""")
    }
}
