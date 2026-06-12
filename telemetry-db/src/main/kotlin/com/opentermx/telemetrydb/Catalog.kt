package com.opentermx.telemetrydb

import java.sql.Connection
import org.slf4j.LoggerFactory

/**
 * Repositorio del catálogo de marcas/tipos/modelos y de los métodos de gestión por
 * dispositivo (Fase 6A). Mismas reglas que el resto del módulo: bind params siempre,
 * errores logueados sin lanzar al camino de consulta.
 *
 * Regla de `source` (error #52): 'operator' es intocable por los packs; un pack solo
 * actualiza entradas de su MISMO source. La hace cumplir [CatalogPackImporter] — este
 * repo expone las primitivas.
 */
class CatalogRepository internal constructor(private val db: TelemetryDb) {

    private val log = LoggerFactory.getLogger(javaClass)

    // ------------------------------------------------------------------ marcas

    /** Devuelve el id de la marca, creándola si falta. No degrada el source existente. */
    fun ensureBrand(name: String, vendor: String, source: String, conn: Connection): Long {
        conn.prepareStatement("SELECT id FROM catalog_brands WHERE lower(name) = lower(?)").use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs -> if (rs.next()) return rs.getLong(1) }
        }
        conn.prepareStatement(
            "INSERT INTO catalog_brands (name, vendor, source) VALUES (?, ?::vendor_t, ?) RETURNING id"
        ).use { ps ->
            ps.setString(1, name)
            ps.setString(2, vendor)
            ps.setString(3, source)
            ps.executeQuery().use { rs -> rs.next(); return rs.getLong(1) }
        }
    }

    fun listBrands(): List<Map<String, Any?>> = runCatching {
        db.withConnection { conn ->
            conn.queryToMaps(
                "SELECT id, name, vendor::text AS vendor, enabled, source FROM catalog_brands ORDER BY lower(name)"
            ) { }
        }
    }.getOrDefault(emptyList())

    /** Alta manual desde la UI: source='operator'. Devuelve id o null si ya existe/falla. */
    fun createBrand(name: String, vendor: String): Long? = runCatching {
        db.withConnection { conn ->
            conn.prepareStatement(
                "INSERT INTO catalog_brands (name, vendor, source) VALUES (?, ?::vendor_t, 'operator') " +
                    "ON CONFLICT (name) DO NOTHING RETURNING id"
            ).use { ps ->
                ps.setString(1, name.trim())
                ps.setString(2, vendor)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
            }
        }
    }.onFailure { log.warn("createBrand `{}` falló: {}", name, it.message) }.getOrNull()

    // ------------------------------------------------------------------ tipos

    fun ensureDeviceType(name: String, conn: Connection): Long {
        conn.prepareStatement(
            "INSERT INTO catalog_device_types (name) VALUES (?) ON CONFLICT (name) DO NOTHING"
        ).use { ps -> ps.setString(1, name); ps.executeUpdate() }
        conn.prepareStatement("SELECT id FROM catalog_device_types WHERE name = ?").use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs -> rs.next(); return rs.getLong(1) }
        }
    }

    fun listDeviceTypes(): List<String> = runCatching {
        db.withConnection { conn ->
            conn.queryToMaps("SELECT name FROM catalog_device_types ORDER BY name") { }
                .mapNotNull { it["name"] as? String }
        }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ modelos

    data class ModelRow(
        val id: Long,
        val brandId: Long,
        val brandName: String,
        val name: String,
        val deviceType: String,
        val family: String?,
        val matchPatterns: List<String>,
        val defaultMethods: List<String>,
        val metadataJson: String,
        val source: String,
        val enabled: Boolean,
    )

    fun listModels(brandId: Long? = null): List<ModelRow> = runCatching {
        db.withConnection { conn ->
            val where = if (brandId != null) "WHERE m.brand_id = ?" else ""
            conn.prepareStatement(
                """
                SELECT m.id, m.brand_id, b.name AS brand_name, m.name, t.name AS device_type,
                       m.family, m.match_patterns, m.default_methods::text[] AS default_methods,
                       m.metadata::text AS metadata_json, m.source, m.enabled
                FROM catalog_models m
                JOIN catalog_brands b ON b.id = m.brand_id
                JOIN catalog_device_types t ON t.id = m.device_type_id
                $where
                ORDER BY lower(b.name), lower(m.name)
                """.trimIndent()
            ).use { ps ->
                if (brandId != null) ps.setLong(1, brandId)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<ModelRow>()
                    while (rs.next()) out += rowToModel(rs)
                    out
                }
            }
        }
    }.onFailure { log.warn("listModels falló: {}", it.message) }.getOrDefault(emptyList())

    fun findModelById(id: Long): ModelRow? = listModels().firstOrNull { it.id == id }

    /** Modelos referenciados por al menos un device (para el MD de gestión, Fase 6D). */
    fun modelsInUse(): List<ModelRow> = runCatching {
        val ids = db.withConnection { conn ->
            conn.queryToMaps(
                "SELECT DISTINCT catalog_model_id FROM devices WHERE catalog_model_id IS NOT NULL"
            ) { }.mapNotNull { (it["catalog_model_id"] as? Number)?.toLong() }.toSet()
        }
        listModels().filter { it.id in ids }
    }.onFailure { log.warn("modelsInUse falló: {}", it.message) }.getOrDefault(emptyList())

    /**
     * Match del modelo del fingerprint (Fase 5) contra `match_patterns` del catálogo.
     * Devuelve TODOS los matches con el string capturado (error #54: el operador ve qué
     * matcheó y por qué). El asistente de onboarding usa el primero como sugerencia.
     */
    data class ModelMatch(val model: ModelRow, val pattern: String, val captured: String)

    fun findMatchingModels(fingerprintModel: String): List<ModelMatch> {
        if (fingerprintModel.isBlank()) return emptyList()
        return listModels().filter { it.enabled }.mapNotNull { model ->
            model.matchPatterns.firstNotNullOfOrNull { pattern ->
                runCatching { Regex(pattern).find(fingerprintModel) }.getOrNull()
                    ?.let { ModelMatch(model, pattern, it.value) }
            }
        }
    }

    /** Upsert de modelo respetando la regla de source. Uso interno del importer y la UI. */
    fun upsertModel(
        conn: Connection,
        brandId: Long,
        deviceTypeId: Long,
        name: String,
        family: String?,
        matchPatterns: List<String>,
        defaultMethods: List<String>,
        metadataJson: String,
        source: String,
    ): UpsertOutcome {
        val existing = conn.queryToMaps(
            "SELECT id, source FROM catalog_models WHERE brand_id = ? AND lower(name) = lower(?)"
        ) { ps -> ps.setLong(1, brandId); ps.setString(2, name) }.firstOrNull()

        if (existing == null) {
            conn.prepareStatement(
                """
                INSERT INTO catalog_models
                  (brand_id, device_type_id, name, family, match_patterns, default_methods, metadata, source)
                VALUES (?, ?, ?, ?, ?, ?::mgmt_method_t[], ?::jsonb, ?)
                RETURNING id
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, brandId)
                ps.setLong(2, deviceTypeId)
                ps.setString(3, name)
                ps.setString(4, family)
                ps.setArray(5, conn.createArrayOf("text", matchPatterns.toTypedArray()))
                ps.setArray(6, conn.createArrayOf("text", defaultMethods.toTypedArray()))
                ps.setString(7, metadataJson)
                ps.setString(8, source)
                ps.executeQuery().use { rs -> rs.next(); return UpsertOutcome.Created(rs.getLong(1)) }
            }
        }
        val existingSource = existing["source"].toString()
        val existingId = (existing["id"] as Number).toLong()
        if (existingSource == "operator" && source != "operator") {
            return UpsertOutcome.SkippedOperator(existingId) // error #52: jamás pisar al operador
        }
        if (existingSource != source && source != "operator") {
            return UpsertOutcome.SkippedOtherSource(existingId, existingSource)
        }
        conn.prepareStatement(
            """
            UPDATE catalog_models SET device_type_id = ?, family = ?, match_patterns = ?,
                   default_methods = ?::mgmt_method_t[], metadata = ?::jsonb, source = ?
            WHERE id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, deviceTypeId)
            ps.setString(2, family)
            ps.setArray(3, conn.createArrayOf("text", matchPatterns.toTypedArray()))
            ps.setArray(4, conn.createArrayOf("text", defaultMethods.toTypedArray()))
            ps.setString(5, metadataJson)
            ps.setString(6, source)
            ps.setLong(7, existingId)
            ps.executeUpdate()
        }
        return UpsertOutcome.Updated(existingId)
    }

    sealed interface UpsertOutcome {
        val modelId: Long

        data class Created(override val modelId: Long) : UpsertOutcome
        data class Updated(override val modelId: Long) : UpsertOutcome
        data class SkippedOperator(override val modelId: Long) : UpsertOutcome
        data class SkippedOtherSource(override val modelId: Long, val source: String) : UpsertOutcome
    }

    /** Alta/edición manual desde la UI (source='operator', que sí puede pisar lo suyo y lo de packs). */
    fun saveOperatorModel(
        brandId: Long,
        deviceType: String,
        name: String,
        family: String?,
        matchPatterns: List<String>,
        defaultMethods: List<String>,
        metadataJson: String = "{}",
    ): Long? = runCatching {
        db.inTransaction { conn ->
            val typeId = ensureDeviceType(deviceType, conn)
            upsertModel(
                conn, brandId, typeId, name.trim(), family?.trim()?.ifEmpty { null },
                matchPatterns, defaultMethods.ifEmpty { listOf("CLI_SSH") }, metadataJson,
                source = "operator",
            ).modelId
        }
    }.onFailure { log.warn("saveOperatorModel `{}` falló: {}", name, it.message) }.getOrNull()

    fun deleteModel(modelId: Long): Boolean = runCatching {
        db.withConnection { conn ->
            conn.prepareStatement("DELETE FROM catalog_models WHERE id = ?").use { ps ->
                ps.setLong(1, modelId)
                ps.executeUpdate() > 0
            }
        }
    }.onFailure { log.warn("deleteModel {} falló: {}", modelId, it.message) }.getOrDefault(false)

    // ------------------------------------------------------------------ devices ⇄ catálogo

    fun assignCatalogModel(deviceId: Long, modelId: Long?): Boolean = runCatching {
        db.withConnection { conn ->
            conn.prepareStatement("UPDATE devices SET catalog_model_id = ?, updated_at = now() WHERE id = ?").use { ps ->
                if (modelId != null) ps.setLong(1, modelId) else ps.setNull(1, java.sql.Types.BIGINT)
                ps.setLong(2, deviceId)
                ps.executeUpdate() > 0
            }
        }
    }.onFailure { log.warn("assignCatalogModel device={} falló: {}", deviceId, it.message) }.getOrDefault(false)

    fun catalogModelOf(deviceId: Long): ModelRow? = runCatching {
        val modelId = db.withConnection { conn ->
            conn.queryToMaps("SELECT catalog_model_id FROM devices WHERE id = ?") { it.setLong(1, deviceId) }
                .firstOrNull()?.get("catalog_model_id") as? Number
        }?.toLong() ?: return null
        findModelById(modelId)
    }.getOrNull()

    // ------------------------------------------------------------------ métodos por device

    /**
     * Habilita/deshabilita un método de gestión para un device (error #55: opt-in
     * trazable — quién y cuándo). `config` es JSON por método (baseUrl, overrides...).
     */
    fun setManagementMethod(
        deviceId: Long,
        method: String,
        enabled: Boolean,
        configJson: String = "{}",
        credentialRef: String? = null,
        enabledBy: String = "operator",
    ): Boolean = runCatching {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO device_management_settings (device_id, method, enabled, config, credential_ref, enabled_by, enabled_at)
                VALUES (?, ?::mgmt_method_t, ?, ?::jsonb, ?, ?, CASE WHEN ? THEN now() ELSE NULL END)
                ON CONFLICT (device_id, method) DO UPDATE SET
                  enabled = EXCLUDED.enabled,
                  config = EXCLUDED.config,
                  credential_ref = EXCLUDED.credential_ref,
                  enabled_by = EXCLUDED.enabled_by,
                  enabled_at = CASE WHEN EXCLUDED.enabled THEN now() ELSE device_management_settings.enabled_at END
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, deviceId)
                ps.setString(2, method)
                ps.setBoolean(3, enabled)
                ps.setString(4, configJson)
                ps.setString(5, credentialRef)
                ps.setString(6, enabledBy)
                ps.setBoolean(7, enabled)
                ps.executeUpdate()
            }
        }
        true
    }.onFailure { log.warn("setManagementMethod device={} {} falló: {}", deviceId, method, it.message) }
        .getOrDefault(false)

    fun managementSettingsOf(deviceId: Long): List<Map<String, Any?>> = runCatching {
        db.withConnection { conn ->
            conn.queryToMaps(
                "SELECT method::text AS method, enabled, config::text AS config_json, credential_ref, " +
                    "enabled_by, enabled_at FROM device_management_settings WHERE device_id = ? ORDER BY method"
            ) { it.setLong(1, deviceId) }
        }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ helpers

    private fun rowToModel(rs: java.sql.ResultSet): ModelRow = ModelRow(
        id = rs.getLong("id"),
        brandId = rs.getLong("brand_id"),
        brandName = rs.getString("brand_name"),
        name = rs.getString("name"),
        deviceType = rs.getString("device_type"),
        family = rs.getString("family"),
        matchPatterns = (rs.getArray("match_patterns")?.array as? Array<*>)
            ?.mapNotNull { it?.toString() } ?: emptyList(),
        defaultMethods = (rs.getArray("default_methods")?.array as? Array<*>)
            ?.mapNotNull { it?.toString() } ?: emptyList(),
        metadataJson = rs.getString("metadata_json") ?: "{}",
        source = rs.getString("source"),
        enabled = rs.getBoolean("enabled"),
    )

    companion object {
        /** Valores válidos de `mgmt_method_t` — para validar packs y UI sin tocar la BD. */
        val MGMT_METHODS = listOf("CLI_SSH", "CLI_SERIAL", "NETMIKO", "ANSIBLE", "REST_API", "SNMP")
    }
}
