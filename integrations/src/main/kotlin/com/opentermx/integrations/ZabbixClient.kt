package com.opentermx.integrations

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.net.http.HttpRequest
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

/**
 * Cliente READ-ONLY de la API JSON-RPC de Zabbix (`<base_url>/api_jsonrpc.php`).
 *
 * Trampas del spec que este cliente resuelve explícitamente:
 *  - **Modo de auth según versión** (error #22): Zabbix ≥ 6.4/7.x usa header
 *    `Authorization: Bearer <token>` y RECHAZA el campo `auth` en el body; 5.x–6.0
 *    usan el campo `auth`. Se detecta con `apiinfo.version` (no requiere auth), con
 *    override manual vía `extra.apiVersion`. Si el secreto es `usuario:password`,
 *    en modo legacy se hace `user.login` y se cachea el session token.
 *  - **`history.get` con value_type equivocado devuelve vacío sin error** (error #23):
 *    siempre se resuelve primero con `item.get` (`output: [itemid,value_type,key_,units]`)
 *    y se usa `value_type` como parámetro `history`.
 *  - **Epoch en SEGUNDOS** (error #24): `time_from`/`time_till` se convierten desde
 *    ISO-8601 explícitamente a segundos, nunca milisegundos.
 *  - **Rangos > 7 días van a `trend.get`** (housekeeping borra history): el resultado
 *    indica `source: "trend"` para que el cliente sepa que son agregados.
 */
class ZabbixClient(
    private val integration: MonitoringIntegration,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val http = HttpSupport.newClient(integration.verifyTls, integration.name)
    private val endpoint = integration.baseUrl.trimEnd('/') + "/api_jsonrpc.php"

    private enum class AuthMode { BEARER, LEGACY_AUTH_FIELD }

    private val detectedMode = AtomicReference<AuthMode?>(null)
    private val legacySession = AtomicReference<String?>(null)
    private var requestId = 1

    // ------------------------------------------------------------------ API pública

    /**
     * Histórico de un item. Resuelve host → item (con `value_type`) → history o trend
     * según el rango. [itemKeyPattern] busca por `key_` (search, no exact).
     */
    fun getHistory(
        hostName: String,
        itemKeyPattern: String,
        fromIso: String,
        toIso: String?,
        limit: Int,
    ): Map<String, Any?> {
        val from = epochSeconds(fromIso, "fromIso")
        val till = toIso?.let { epochSeconds(it, "toIso") }

        val hostId = resolveHostId(hostName)
        val items = call(
            "item.get",
            mapOf(
                "hostids" to listOf(hostId),
                "search" to mapOf("key_" to itemKeyPattern),
                "output" to listOf("itemid", "value_type", "key_", "units"),
                "limit" to 10,
            ),
        )
        if (items.isEmpty || items.size() == 0) {
            throw IntegrationException(
                "Zabbix no tiene items que matcheen `$itemKeyPattern` en el host `$hostName`",
            )
        }
        val item = items[0]
        val itemId = item["itemid"].asText()
        val valueType = item["value_type"].asInt()

        val rangeDays = Duration.ofSeconds((till ?: nowSeconds()) - from).toDays()
        val useTrends = rangeDays > HISTORY_MAX_DAYS
        val params = mutableMapOf<String, Any?>(
            "itemids" to listOf(itemId),
            "time_from" to from,
            "sortfield" to "clock",
            "sortorder" to "ASC",
            "limit" to limit,
        )
        if (till != null) params["time_till"] = till
        if (!useTrends) params["history"] = valueType

        val rows = call(if (useTrends) "trend.get" else "history.get", params)
        return linkedMapOf(
            "source" to if (useTrends) "trend" else "history",
            "item" to mapper.convertValue(item, Map::class.java),
            "values" to mapper.convertValue(rows, List::class.java),
        )
    }

    /** Problemas activos, opcionalmente filtrados por host y severidad mínima. */
    fun getActiveProblems(hostName: String?, minSeverity: Int, limit: Int): List<Map<String, Any?>> {
        val params = mutableMapOf<String, Any?>(
            "output" to "extend",
            "recent" to false,
            "sortfield" to listOf("eventid"),
            "sortorder" to "DESC",
            "limit" to limit,
            "severities" to (minSeverity..5).toList(),
        )
        if (hostName != null) params["hostids"] = listOf(resolveHostId(hostName))
        val rows = call("problem.get", params)
        @Suppress("UNCHECKED_CAST")
        return mapper.convertValue(rows, List::class.java) as List<Map<String, Any?>>
    }

    // ------------------------------------------------------------------ JSON-RPC core

    private fun resolveHostId(hostName: String): String {
        val hosts = call(
            "host.get",
            mapOf("filter" to mapOf("host" to listOf(hostName)), "output" to listOf("hostid", "host")),
        )
        if (hosts.isEmpty || hosts.size() == 0) {
            throw IntegrationException("Zabbix no conoce el host `$hostName` (host.get vacío)")
        }
        return hosts[0]["hostid"].asText()
    }

    /** Llamada autenticada según el modo detectado. */
    internal fun call(method: String, params: Any): JsonNode {
        val mode = authMode()
        val secret = integration.secretProvider()
        val body = mutableMapOf<String, Any?>(
            "jsonrpc" to "2.0",
            "method" to method,
            "params" to params,
            "id" to requestId++,
        )
        val builder = HttpSupport.request(endpoint)
            .header("Content-Type", "application/json-rpc")
        when (mode) {
            AuthMode.BEARER -> builder.header("Authorization", "Bearer ${tokenFor(secret)}")
            AuthMode.LEGACY_AUTH_FIELD -> body["auth"] = legacyToken(secret)
        }
        val response = HttpSupport.execute(
            http,
            builder.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
            secret,
            "Zabbix $method",
        )
        val parsed = mapper.readTree(response)
        parsed["error"]?.let { err ->
            throw IntegrationException(
                "Zabbix $method devolvió error: " +
                    HttpSupport.scrub("${err["message"]?.asText()} — ${err["data"]?.asText()}", secret),
            )
        }
        return parsed["result"] ?: mapper.createArrayNode()
    }

    /**
     * `apiinfo.version` NO requiere auth (y en 7.x falla si se la mandás). Cacheado.
     * Override: `extra.apiVersion` (p. ej. "6.0" fuerza legacy, "7.0" fuerza Bearer).
     */
    private fun authMode(): AuthMode {
        detectedMode.get()?.let { return it }
        val version = integration.extra["apiVersion"] ?: fetchVersion()
        val mode = if (isBearerVersion(version)) AuthMode.BEARER else AuthMode.LEGACY_AUTH_FIELD
        log.info("Integración Zabbix `{}`: versión {} => modo {}", integration.name, version, mode)
        detectedMode.set(mode)
        return mode
    }

    private fun fetchVersion(): String {
        val body = mapOf(
            "jsonrpc" to "2.0", "method" to "apiinfo.version", "params" to emptyMap<String, Any>(), "id" to 1,
        )
        val response = HttpSupport.execute(
            http,
            HttpSupport.request(endpoint)
                .header("Content-Type", "application/json-rpc")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build(),
            secret = "",
            what = "Zabbix apiinfo.version",
        )
        return mapper.readTree(response)["result"]?.asText()
            ?: throw IntegrationException("apiinfo.version sin `result` — ¿es un endpoint Zabbix?")
    }

    /** Bearer desde 6.4 (6.4, 7.x, ...). 5.x y 6.0/6.2 => legacy. */
    private fun isBearerVersion(version: String): Boolean {
        val parts = version.split('.').mapNotNull { it.toIntOrNull() }
        val major = parts.getOrNull(0) ?: return true // versión rara: asumimos moderna
        val minor = parts.getOrNull(1) ?: 0
        return major > 6 || (major == 6 && minor >= 4)
    }

    /** En modo Bearer el secreto ES el API token — user:pass no aplica en ≥ 6.4. */
    private fun tokenFor(secret: String): String = secret

    /**
     * Modo legacy: si el secreto es `usuario:password` se hace `user.login` una vez y
     * se cachea el session token; si no, se asume API token (Zabbix ≥ 5.4) y va directo
     * en el campo `auth`.
     */
    private fun legacyToken(secret: String): String {
        if (!secret.contains(':')) return secret
        legacySession.get()?.let { return it }
        val user = secret.substringBefore(':')
        val password = secret.substringAfter(':')
        val body = mapOf(
            "jsonrpc" to "2.0",
            "method" to "user.login",
            "params" to mapOf("username" to user, "password" to password),
            "id" to requestId++,
        )
        val response = HttpSupport.execute(
            http,
            HttpSupport.request(endpoint)
                .header("Content-Type", "application/json-rpc")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build(),
            secret = password,
            what = "Zabbix user.login",
        )
        val token = mapper.readTree(response)["result"]?.asText()
            ?: throw IntegrationException("user.login no devolvió session token")
        legacySession.set(token)
        return token
    }

    private fun epochSeconds(iso: String, field: String): Long = runCatching {
        OffsetDateTime.parse(iso).toEpochSecond()
    }.getOrElse {
        throw IntegrationException("`$field` debe ser ISO-8601 con zona (ej. 2026-06-01T00:00:00Z)")
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    companion object {
        /** Más allá de esto, history puede estar purgado por housekeeping: usar trends. */
        const val HISTORY_MAX_DAYS = 7
    }
}
