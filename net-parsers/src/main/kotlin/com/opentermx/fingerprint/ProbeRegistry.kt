package com.opentermx.fingerprint

import com.opentermx.fingerprint.probes.ArubaCxShowSystemProbe
import com.opentermx.fingerprint.probes.CiscoShowVersionProbe
import com.opentermx.fingerprint.probes.ComwareDisplayVersionProbe
import com.opentermx.fingerprint.probes.FortinetGetStatusProbe
import com.opentermx.fingerprint.probes.HuaweiDisplayVersionProbe
import com.opentermx.fingerprint.probes.MikrotikSystemResourceProbe
import com.opentermx.netparsers.Vendor

/**
 * Catálogo de sondas de fingerprinting (subfase 5A). Cara pública para el orquestador
 * `FingerprintService` de mcp-server: dado un vendor ya detectado devuelve SU sonda;
 * sin vendor, la cadena completa ordenada por [FingerprintProbe.order].
 *
 * Agregar un vendor nuevo = una clase de sonda + fixtures, sin tocar el orquestador.
 */
object ProbeRegistry {

    private val cisco = CiscoShowVersionProbe()
    private val huawei = HuaweiDisplayVersionProbe()
    private val arubaCx = ArubaCxShowSystemProbe()
    private val comware = ComwareDisplayVersionProbe()
    private val fortinet = FortinetGetStatusProbe()
    private val mikrotik = MikrotikSystemResourceProbe()

    /** Cadena completa, ordenada por prioridad de intento. */
    fun all(): List<FingerprintProbe> =
        listOf(cisco, huawei, arubaCx, comware, fortinet, mikrotik).sortedBy { it.order }

    /**
     * Sonda del vendor ya detectado por OpenTermX (la cadena no se recorre: un solo
     * intento). `null` = vendor sin sonda — el orquestador cae a la cadena completa.
     */
    fun forVendor(vendor: Vendor): FingerprintProbe? = when (vendor) {
        Vendor.CISCO_IOS, Vendor.CISCO_IOSXE, Vendor.CISCO_NXOS -> cisco
        Vendor.HUAWEI_VRP -> huawei
        Vendor.HPE_COMWARE -> comware
        Vendor.ARUBA_AOSCX, Vendor.ARUBA_PROVISION -> arubaCx
        Vendor.FORTINET -> fortinet
        Vendor.MIKROTIK -> mikrotik
        Vendor.JUNIPER_JUNOS, Vendor.GENERIC, Vendor.UNKNOWN -> null
    }
}
