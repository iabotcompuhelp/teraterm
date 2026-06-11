package com.opentermx.integrations

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Cliente READ-ONLY de la API REST de ManageEngine OpManager. La autenticación es por
 * parámetro `apiKey` en cada request.
 *
 * **Las rutas y los campos varían entre builds de OpManager** (error #27 del catálogo):
 * el mapeo es tolerante (Jackson con FAIL_ON_UNKNOWN_PROPERTIES off + lectura por
 * árbol) y, cuando la estructura no se reconoce, se devuelve el JSON crudo con
 * `rawAvailable: true` en lugar de romper.
 */
class OpManagerClient(
    private val integration: MonitoringIntegration,
) {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    private val http = HttpSupport.newClient(integration.verifyTls, integration.name)
    private val base = integration.baseUrl.trimEnd('/')

    /** Lista alarmas, opcionalmente filtradas por device y severidad. */
    fun listAlarms(deviceName: String?, severity: String?, limit: Int): Map<String, Any?> {
        val params = buildMap {
            if (deviceName != null) put("deviceName", deviceName)
            if (severity != null) put("severity", severity)
            // distintos builds aceptan distintos nombres de paginación — mandamos ambos.
            put("rowCount", limit.toString())
            put("fetchCount", limit.toString())
        }
        val node = get("/api/json/alarm/listAlarms", params)
        val alarms = extractArray(node, "alarms", "alarm", "Alarms")
        return if (alarms != null) {
            linkedMapOf(
                "alarms" to alarms.take(limit).map { mapAlarm(it) },
                "rawAvailable" to false,
            )
        } else {
            linkedMapOf(
                "alarms" to emptyList<Any?>(),
                "rawAvailable" to true,
                "raw" to treeToAny(node),
            )
        }
    }

    /** Performance histórica de un device/monitor. Estructura variable: tolerante + raw. */
    fun getPerformance(
        deviceName: String,
        monitorName: String?,
        period: String,
        fromIso: String?,
        toIso: String?,
    ): Map<String, Any?> {
        val params = buildMap {
            put("deviceName", deviceName)
            if (monitorName != null) put("monitorName", monitorName)
            put("period", period)
            if (fromIso != null) put("fromTime", fromIso)
            if (toIso != null) put("toTime", toIso)
        }
        val node = get("/api/json/device/getPerformanceData", params)
        val series = extractArray(node, "performanceData", "data", "series", "graphData")
        return if (series != null) {
            linkedMapOf("series" to treeToAny(series), "rawAvailable" to false)
        } else {
            linkedMapOf("series" to emptyList<Any?>(), "rawAvailable" to true, "raw" to treeToAny(node))
        }
    }

    // ------------------------------------------------------------------ internals

    private fun get(path: String, params: Map<String, String>): JsonNode {
        val secret = integration.secretProvider()
        val query = (params + ("apiKey" to secret)).entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
        }
        val response = HttpSupport.execute(
            http,
            HttpSupport.request("$base$path?$query").GET().build(),
            secret,
            "OpManager $path",
        )
        val node = mapper.readTree(response)
        // OpManager reporta errores como {"error": {...}} con HTTP 200 en varios builds.
        node["error"]?.let { err ->
            throw IntegrationException(
                "OpManager $path devolvió error: " + HttpSupport.scrub(err.toString().take(300), secret),
            )
        }
        return node
    }

    /** Busca el primer array bajo alguna de las claves conocidas (o el nodo raíz si ES array). */
    private fun extractArray(node: JsonNode, vararg keys: String): JsonNode? {
        if (node.isArray) return node
        for (key in keys) {
            val candidate = node[key]
            if (candidate != null && candidate.isArray) return candidate
        }
        return null
    }

    /** Campos canónicos best-effort; lo no mapeado queda accesible en `raw` del item. */
    private fun mapAlarm(alarm: JsonNode): Map<String, Any?> = linkedMapOf(
        "device" to firstText(alarm, "deviceName", "displayName", "entity"),
        "severity" to firstText(alarm, "severity", "stringSeverity"),
        "message" to firstText(alarm, "message", "alarmMessage", "displayMessage"),
        "time" to firstText(alarm, "modTime", "time", "alarmTime"),
        "acknowledged" to firstText(alarm, "ackBy", "acknowledged"),
        "raw" to treeToAny(alarm),
    )

    private fun firstText(node: JsonNode, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { node[it]?.takeIf { n -> !n.isNull }?.asText() }

    private fun treeToAny(node: JsonNode): Any? = mapper.convertValue(node, Any::class.java)
}
