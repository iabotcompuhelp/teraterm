package com.opentermx.telemetrydb

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory

/**
 * Importador de "catalog packs" YAML (Fase 6A): marcas/modelos extensibles SIN tocar
 * código. Fuentes: packs embebidos en resources (`/catalog-packs/`) y los archivos
 * `.yaml` del operador en `~/.opentermx/catalog/`.
 *
 * Reglas (errores #52/#53):
 *  - **Validación completa ANTES del primer INSERT**: el YAML entero se parsea y valida
 *    (versión, campos, regex compilables, métodos válidos); un pack inválido se rechaza
 *    COMPLETO con el reporte de errores por modelo y campo — jamás importación a medias.
 *  - **Transacción única** por pack: o entra todo o no entra nada.
 *  - **Idempotente** por (brand, model.name): re-importar es no-op/update.
 *  - **`source='operator'` es intocable**: el pack lo reporta como skipped, no lo pisa.
 *    Un pack solo actualiza entradas de su MISMO source (`pack:<archivo>`).
 */
class CatalogPackImporter(private val db: TelemetryDb) {

    data class PackFile(
        val packVersion: Int = 0,
        val brand: String = "",
        val vendorEnum: String? = null,
        val models: List<PackModel> = emptyList(),
    )

    data class PackModel(
        val name: String = "",
        val deviceType: String = "",
        val family: String? = null,
        val matchPatterns: List<String> = emptyList(),
        val defaultMethods: List<String> = emptyList(),
        val metadata: Map<String, Any?> = emptyMap(),
    )

    data class ImportResult(
        val packSource: String,
        val brand: String,
        val created: Int,
        val updated: Int,
        val skippedOperator: Int,
        val skippedOtherSource: Int,
        val errors: List<String>,
    ) {
        val ok: Boolean get() = errors.isEmpty()
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val yaml: ObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    /** Importa un pack desde texto YAML. [fileName] define el source `pack:<archivo>`. */
    fun importPack(yamlText: String, fileName: String): ImportResult {
        val source = "pack:$fileName"
        val pack = runCatching { yaml.readValue(yamlText, PackFile::class.java) }
            .getOrElse { e ->
                return ImportResult(source, "?", 0, 0, 0, 0, listOf("YAML inválido: ${e.message?.take(200)}"))
            }
        val errors = validate(pack)
        if (errors.isNotEmpty()) {
            log.warn("Pack `{}` rechazado COMPLETO: {} errores", fileName, errors.size)
            return ImportResult(source, pack.brand, 0, 0, 0, 0, errors)
        }

        return runCatching {
            var created = 0
            var updated = 0
            var skippedOperator = 0
            var skippedOther = 0
            db.inTransaction { conn ->
                val brandId = db.catalog.ensureBrand(
                    pack.brand, pack.vendorEnum ?: "UNKNOWN", source, conn,
                )
                for (model in pack.models) {
                    val typeId = db.catalog.ensureDeviceType(model.deviceType, conn)
                    val outcome = db.catalog.upsertModel(
                        conn, brandId, typeId, model.name, model.family,
                        model.matchPatterns,
                        model.defaultMethods.ifEmpty { listOf("CLI_SSH") },
                        metadataJson = ProfileMigrator.toJson(model.metadata),
                        source = source,
                    )
                    when (outcome) {
                        is CatalogRepository.UpsertOutcome.Created -> created++
                        is CatalogRepository.UpsertOutcome.Updated -> updated++
                        is CatalogRepository.UpsertOutcome.SkippedOperator -> skippedOperator++
                        is CatalogRepository.UpsertOutcome.SkippedOtherSource -> skippedOther++
                    }
                }
            }
            ImportResult(source, pack.brand, created, updated, skippedOperator, skippedOther, emptyList())
                .also {
                    log.info(
                        "Pack `{}` importado: {} creados, {} actualizados, {} operator intactos, {} de otro pack",
                        fileName, it.created, it.updated, it.skippedOperator, it.skippedOtherSource,
                    )
                }
        }.getOrElse { e ->
            ImportResult(source, pack.brand, 0, 0, 0, 0, listOf("import falló (rollback): ${e.message?.take(200)}"))
        }
    }

    /** Importa todos los packs embebidos en resources (`/catalog-packs/<nombre>.yaml`). */
    fun importBuiltins(): List<ImportResult> = BUILTIN_PACKS.mapNotNull { name ->
        val text = javaClass.getResourceAsStream("/catalog-packs/$name")
            ?.bufferedReader()?.use { it.readText() }
        if (text == null) {
            log.warn("Pack embebido /catalog-packs/{} ausente del classpath", name)
            null
        } else importPack(text, name)
    }

    /** Importa los packs `.yaml` del operador en `~/.opentermx/catalog/`. Nunca lanza. */
    fun importUserDir(dir: Path = USER_CATALOG_DIR): List<ImportResult> = runCatching {
        if (!Files.isDirectory(dir)) return emptyList()
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".yaml") || it.fileName.toString().endsWith(".yml") }
                .sorted()
                .map { path -> importPack(Files.readString(path), path.fileName.toString()) }
                .toList()
        }
    }.onFailure { log.warn("Import de packs de {} falló: {}", dir, it.message) }
        .getOrDefault(emptyList())

    /**
     * Exporta los modelos de una marca como pack YAML (para compartir entre
     * instalaciones). Null si la marca no existe o no tiene modelos.
     */
    fun exportBrandPack(brandName: String): String? = runCatching {
        val brand = db.catalog.listBrands()
            .firstOrNull { it["name"].toString().equals(brandName, ignoreCase = true) } ?: return null
        val models = db.catalog.listModels((brand["id"] as Number).toLong())
        if (models.isEmpty()) return null
        val json = ObjectMapper().registerKotlinModule()
        yaml.writeValueAsString(
            linkedMapOf(
                "packVersion" to SUPPORTED_PACK_VERSION,
                "brand" to brand["name"],
                "vendorEnum" to brand["vendor"],
                "models" to models.map { m ->
                    linkedMapOf(
                        "name" to m.name,
                        "deviceType" to m.deviceType,
                        "family" to m.family,
                        "matchPatterns" to m.matchPatterns,
                        "defaultMethods" to m.defaultMethods,
                        "metadata" to json.readValue(m.metadataJson, Map::class.java),
                    )
                },
            )
        )
    }.onFailure { log.warn("export del pack `{}` falló: {}", brandName, it.message) }.getOrNull()

    // ------------------------------------------------------------------ validación

    /** Reporte por modelo y campo (error #53) — nada se inserta si esta lista no es vacía. */
    private fun validate(pack: PackFile): List<String> {
        val errors = mutableListOf<String>()
        if (pack.packVersion != SUPPORTED_PACK_VERSION) {
            errors += "packVersion ${pack.packVersion} no soportada (esperaba $SUPPORTED_PACK_VERSION)"
        }
        if (pack.brand.isBlank()) errors += "campo `brand` vacío"
        pack.vendorEnum?.let { vendor ->
            if (runCatching { com.opentermx.netparsers.Vendor.valueOf(vendor) }.isFailure) {
                errors += "vendorEnum `$vendor` no existe en el enum Vendor"
            }
        }
        if (pack.models.isEmpty()) errors += "el pack no declara modelos"
        pack.models.forEachIndexed { i, model ->
            val at = "models[$i]" + if (model.name.isNotBlank()) " (`${model.name}`)" else ""
            if (model.name.isBlank()) errors += "$at: campo `name` vacío"
            if (model.deviceType.isBlank()) errors += "$at: campo `deviceType` vacío"
            model.matchPatterns.forEach { pattern ->
                runCatching { Regex(pattern) }.onFailure {
                    errors += "$at: matchPattern `$pattern` no compila: ${it.message?.take(80)}"
                }
            }
            model.defaultMethods.forEach { method ->
                if (method !in CatalogRepository.MGMT_METHODS) {
                    errors += "$at: defaultMethod `$method` inválido " +
                        "(${CatalogRepository.MGMT_METHODS.joinToString("|")})"
                }
            }
        }
        return errors
    }

    companion object {
        const val SUPPORTED_PACK_VERSION = 1

        /** Packs que viajan con la app (catalog-pack-inicial, Fase 6A). */
        val BUILTIN_PACKS = listOf("hpe-comware-campus.yaml", "aruba-aoss-campus.yaml")

        val USER_CATALOG_DIR: Path =
            Path.of(System.getProperty("user.home"), ".opentermx", "catalog")
    }
}
