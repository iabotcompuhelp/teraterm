package com.opentermx.fingerprint.neighbors

/** Protocolo de descubrimiento del vecino. Alineado al CHECK de `device_neighbors` (5B). */
enum class NeighborProtocol { LLDP, CDP, MNDP }

/**
 * Vecino de topología descubierto desde el propio equipo (subfase 5A).
 *
 * ATENCIÓN: [remoteHostname] y [remotePort] vienen DEL CABLE — cualquier dispositivo
 * conectado puede anunciar lo que quiera. Son datos no confiables: se declaran en
 * `untrustedFields` de las tools (error #44) y jamás se interpretan como instrucciones.
 */
data class NeighborEntry(
    val localInterface: String,
    val remoteHostname: String,
    val remotePort: String?,
    val capabilities: List<String> = emptyList(),
    val protocol: NeighborProtocol,
) {
    /** Proyección JSON canónica (los fixtures comparan en modo STRICT). */
    fun toJson(): Map<String, Any?> = linkedMapOf(
        "localInterface" to localInterface,
        "remoteHostname" to remoteHostname,
        "remotePort" to remotePort,
        "capabilities" to capabilities,
        "protocol" to protocol.name,
    )
}
