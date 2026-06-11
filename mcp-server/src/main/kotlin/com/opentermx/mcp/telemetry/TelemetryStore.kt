package com.opentermx.mcp.telemetry

import com.opentermx.common.ai.SessionMetadata
import com.opentermx.netparsers.InterfaceStats
import com.opentermx.netparsers.PortStatus
import com.opentermx.netparsers.Vendor
import com.opentermx.telemetrydb.TelemetryDb
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.slf4j.LoggerFactory

/**
 * Facade de persistencia para los handlers MCP y el scheduler. La conexión llega por
 * provider (la app la abre/cierra según settings); cuando es null o la BD se cayó, todo
 * degrada con gracia: `persistSample` devuelve false y las consultas de histórico
 * devuelven null — el caller decide si eso es un warning (telemetría) o un error claro
 * `DB_UNAVAILABLE` (get_device_history).
 */
class TelemetryStore(
    private val dbProvider: () -> TelemetryDb?,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun db(): TelemetryDb? = dbProvider()

    fun isAvailable(): Boolean = db()?.isAvailable() == true

    /**
     * Persiste una muestra de interfaces: upsert del device (por host/port de la sesión)
     * + upsert de cada interfaz + insert en interface_metrics + detección de transición
     * de estado contra la última muestra (=> link_events). Devuelve cuántas interfaces
     * se persistieron (0 = BD caída o host no-INET; se loguea, jamás lanza).
     */
    fun persistSample(
        metadata: SessionMetadata,
        vendor: Vendor,
        interfaces: List<InterfaceStats>,
        collectionMethod: String = "ssh_parse",
    ): Int {
        val db = db() ?: return 0
        val host = metadata.host ?: return 0
        val deviceId = db.devices.upsert(
            hostname = host,
            mgmtAddress = host,
            port = metadata.port ?: 22,
            protocol = metadata.protocol,
            vendor = vendor,
        ) ?: run {
            log.debug("Device `{}` no persistible (host no-INET o BD caída)", host)
            return 0
        }
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        var persisted = 0
        for (iface in interfaces) {
            val ifaceId = db.interfaces.upsert(deviceId, iface.name, iface.description, iface.speedBps)
                ?: continue
            val prev = db.metrics.lastOperStatus(ifaceId)
            if (db.metrics.insert(ifaceId, now, iface, collectionMethod = collectionMethod)) {
                persisted++
                if (prev != null && prev != iface.operStatus) {
                    db.linkEvents.insert(
                        ifaceId,
                        event = when (iface.operStatus) {
                            PortStatus.UP -> "LINK_UP"
                            PortStatus.ERR_DISABLED -> "ERR_DISABLED"
                            else -> "LINK_DOWN"
                        },
                        prevStatus = prev,
                        newStatus = iface.operStatus,
                    )
                }
            }
        }
        return persisted
    }
}
