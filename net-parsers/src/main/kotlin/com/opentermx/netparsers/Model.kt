package com.opentermx.netparsers

/**
 * Vendor canónico del subsistema de telemetría. Alineado 1:1 con el enum `vendor_t`
 * del esquema PostgreSQL de la Fase 3 — el `name` de este enum es lo que viaja en el
 * JSON de las tools (`"vendor": "CISCO_NXOS"`) y lo que se persiste en la BD.
 *
 * NO confundir con `com.opentermx.ai.context.Vendor` (detección de CLI del módulo
 * ai-assistant): el mapeo entre ambos vive en `mcp-server`, que es el único módulo
 * que conoce los dos mundos.
 */
enum class Vendor {
    CISCO_IOS,
    CISCO_IOSXE,
    CISCO_NXOS,
    ARUBA_AOSCX,
    ARUBA_PROVISION,
    FORTINET,
    HUAWEI_VRP,
    MIKROTIK,
    JUNIPER_JUNOS,
    GENERIC,
    UNKNOWN,
}

enum class PortStatus { UP, DOWN, ERR_DISABLED, ADMIN_DOWN, UNKNOWN }

enum class Duplex { FULL, HALF, AUTO, UNKNOWN }

/**
 * Modelo canónico de una interfaz, independiente del vendor. Regla de oro: campo que
 * el equipo no reporta = `null`, NUNCA un default inventado (p. ej. no se asume la
 * velocidad nominal de un puerto caído con auto-negotiation).
 *
 * Los contadores son `Long` a propósito: varios equipos reportan valores > 2^32
 * (los fixtures lo fuerzan). [raw] lleva extras vendor-specific que el modelo no
 * normaliza — hoy `rxBytes`/`txBytes`, que `get_bandwidth_utilization` usa para el
 * método counter_delta. [raw] NO se serializa en el JSON de las tools.
 */
data class InterfaceStats(
    val name: String,
    val description: String? = null,
    val adminStatus: PortStatus = PortStatus.UNKNOWN,
    val operStatus: PortStatus = PortStatus.UNKNOWN,
    val speedBps: Long? = null,
    val duplex: Duplex? = null,
    val mtu: Int? = null,
    val inputRateBps: Long? = null,
    val outputRateBps: Long? = null,
    val inputPackets: Long? = null,
    val outputPackets: Long? = null,
    val inputErrors: Long? = null,
    val outputErrors: Long? = null,
    val crcErrors: Long? = null,
    val inputDrops: Long? = null,
    val outputDrops: Long? = null,
    val collisions: Long? = null,
    val lastFlap: String? = null,
    val raw: Map<String, String> = emptyMap(),
) {
    /**
     * Proyección JSON canónica — exactamente las 18 claves del `outputSchema` de
     * `get_interface_stats` (los fixtures comparan en modo STRICT: ni una clave de
     * más ni de menos).
     */
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "description" to description,
        "adminStatus" to adminStatus.name,
        "operStatus" to operStatus.name,
        "speedBps" to speedBps,
        "duplex" to duplex?.name,
        "mtu" to mtu,
        "inputRateBps" to inputRateBps,
        "outputRateBps" to outputRateBps,
        "inputPackets" to inputPackets,
        "outputPackets" to outputPackets,
        "inputErrors" to inputErrors,
        "outputErrors" to outputErrors,
        "crcErrors" to crcErrors,
        "inputDrops" to inputDrops,
        "outputDrops" to outputDrops,
        "collisions" to collisions,
        "lastFlap" to lastFlap,
    )
}

/**
 * Resultado de un parse. La regla del módulo: los parsers NUNCA lanzan — input basura
 * devuelve [Failure] con una muestra del raw para diagnóstico.
 */
sealed interface ParseResult<out T> {
    data class Success<T>(val data: T) : ParseResult<T>
    data class PartialSuccess<T>(val data: T, val warnings: List<String>) : ParseResult<T>
    data class Failure(val reason: String, val rawSample: String) : ParseResult<Nothing>
}

/**
 * Mapea el resultado al shape del `outputSchema` de `get_interface_stats`. `warnings`
 * solo aparece cuando hay (STRICT en los fixtures); `rawOutput` solo en Failure para
 * que el LLM cliente pueda al menos leer el texto crudo.
 */
fun ParseResult<List<InterfaceStats>>.toToolOutput(vendor: Vendor): Map<String, Any?> = when (this) {
    is ParseResult.Success -> linkedMapOf(
        "parsed" to true,
        "vendor" to vendor.name,
        "interfaces" to data.map { it.toJson() },
    )
    is ParseResult.PartialSuccess -> linkedMapOf(
        "parsed" to true,
        "vendor" to vendor.name,
        "interfaces" to data.map { it.toJson() },
        "warnings" to warnings,
    )
    is ParseResult.Failure -> linkedMapOf(
        "parsed" to false,
        "vendor" to vendor.name,
        "interfaces" to emptyList<Map<String, Any?>>(),
        "warnings" to listOf(reason),
        "rawOutput" to rawSample,
    )
}

/**
 * Contrato de todo parser de output de equipo (spec Fase 2). [commandPattern] declara
 * qué comando produce el output que este parser entiende — el registry lo usa para
 * elegir parser dado el comando que se ejecutó.
 */
interface OutputParser<T> {
    val vendor: Vendor
    val commandPattern: Regex
    fun parse(raw: String): ParseResult<T>
}
