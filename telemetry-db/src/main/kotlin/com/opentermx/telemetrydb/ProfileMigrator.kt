package com.opentermx.telemetrydb

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory

/**
 * Normaliza el JSONB del perfil al esquema vigente EN MEMORIA (Fase 5B). El JSONB
 * persistido evolucionará: cada lectura pasa por acá y NUNCA se asume que lo leído
 * tiene la forma actual.
 *
 * Regla (error #40): un JSONB corrupto, de versión desconocida o con shape inesperado
 * produce un perfil mínimo válido + warning — JAMÁS una excepción que tumbe
 * `list_sessions` o `get_device_profile`.
 *
 * Cadena de migraciones: hoy v1 es la vigente; cuando exista v2 se agrega un paso
 * `migrateV1toV2` y el `when` de [migrate] lo encadena. El test de contrato carga
 * fixtures de cada versión histórica y verifica que migran a la actual.
 */
object ProfileMigrator {

    const val CURRENT_VERSION = 1

    /** Shape vigente del perfil. Claves SIEMPRE presentes tras migrar. */
    data class Migrated(
        val profile: MutableMap<String, Any?>,
        val warnings: List<String>,
    )

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper().registerKotlinModule()

    fun migrate(rawJson: String?, schemaVersion: Int): Migrated {
        val warnings = mutableListOf<String>()
        val parsed: MutableMap<String, Any?> = if (rawJson.isNullOrBlank()) {
            mutableMapOf()
        } else {
            runCatching {
                @Suppress("UNCHECKED_CAST")
                mapper.readValue(rawJson, Map::class.java) as Map<String, Any?>
            }.getOrElse { e ->
                warnings += "JSONB de perfil corrupto (${e.message?.take(120)}) — perfil mínimo"
                log.warn("profile.jsonb.corrupt — {}", e.message)
                emptyMap()
            }.toMutableMap()
        }

        val migrated = when {
            schemaVersion == CURRENT_VERSION -> parsed
            schemaVersion < CURRENT_VERSION -> parsed // v0/legacy: normalize() completa claves
            else -> {
                warnings += "schema_version $schemaVersion desconocida (soporto hasta $CURRENT_VERSION) — perfil mínimo"
                log.warn("profile.version.unknown version={} — degradando a perfil mínimo", schemaVersion)
                mutableMapOf()
            }
        }
        return Migrated(normalize(migrated), warnings)
    }

    fun minimal(): MutableMap<String, Any?> = normalize(mutableMapOf())

    /** Serialización JSON segura (escaping incluido) para valores que van a jsonb_set. */
    fun toJson(value: Any?): String = mapper.writeValueAsString(value)

    /** Completa toda clave ausente o de tipo inesperado con su default del shape vigente. */
    private fun normalize(profile: MutableMap<String, Any?>): MutableMap<String, Any?> {
        val caps = (profile["capabilities"] as? Map<*, *>).orEmpty()
        profile["capabilities"] = mutableMapOf<String, Any?>(
            "tools" to (caps["tools"] as? List<*> ?: emptyList<String>()),
            "readonlyProfile" to caps["readonlyProfile"] as? String,
            "forbidden" to (caps["forbidden"] as? List<*> ?: emptyList<String>()),
        )
        profile["uplinks"] = profile["uplinks"] as? List<*> ?: emptyList<String>()
        profile["notes"] = profile["notes"] as? String
        profile["maintenanceWindow"] = profile["maintenanceWindow"] as? String
        profile["contact"] = profile["contact"] as? String
        profile["custom"] = (profile["custom"] as? Map<*, *>)?.toMutableMap() ?: mutableMapOf<String, Any?>()
        return profile
    }
}
