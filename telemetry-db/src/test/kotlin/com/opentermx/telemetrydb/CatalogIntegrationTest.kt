package com.opentermx.telemetrydb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * Integración 6A contra PostgreSQL embebido: migración V3 (V3_0 ALTER TYPE fuera de
 * transacción + V3_1 catálogo), importación de packs (idempotente, transaccional, jamás
 * pisa al operador — errores #52/#53), match de modelos (error #54) y habilitación
 * trazable de métodos por dispositivo (error #55).
 *
 * Ordenado: los tests de import comparten el catálogo del PG embebido de la clase.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CatalogIntegrationTest {

    private val db get() = EmbeddedPg.db
    private val importer by lazy { CatalogPackImporter(db) }

    @Test
    @Order(1)
    fun `V3 migra sobre V2 - tablas de catalogo presentes y vendor_t con HPE_COMWARE`() {
        val tables = db.withConnection { conn ->
            conn.queryToMaps(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'opentermx'"
            ) { }
        }.mapNotNull { it["table_name"] as? String }
        assertTrue(
            tables.containsAll(listOf("catalog_brands", "catalog_device_types", "catalog_models", "device_management_settings")),
            "faltan tablas V3: $tables",
        )
        // El valor nuevo del enum es usable (V3_0 corrió fuera de transacción).
        val ok = db.withConnection { conn ->
            conn.queryToMaps("SELECT 'HPE_COMWARE'::opentermx.vendor_t::text AS v") { }
        }.single()["v"]
        assertEquals("HPE_COMWARE", ok)
        assertTrue(db.catalog.listDeviceTypes().containsAll(listOf("switch", "access_point", "firewall")))
    }

    @Test
    @Order(2)
    fun `los packs embebidos importan y re-importar es idempotente`() {
        val first = importer.importBuiltins()
        assertEquals(2, first.size)
        assertTrue(first.all { it.ok }, "errores: ${first.flatMap { it.errors }}")
        assertEquals(3, first.sumOf { it.created }, "5130 EI + 5140 EI + 2930F")

        val again = importer.importBuiltins()
        assertTrue(again.all { it.ok })
        assertEquals(0, again.sumOf { it.created }, "re-import no duplica")
        assertEquals(3, again.sumOf { it.updated }, "re-import actualiza lo suyo")

        val brands = db.catalog.listBrands().map { it["name"] }
        assertTrue(brands.containsAll(listOf("HPE", "Aruba")), "$brands")
        val hpe = db.catalog.listBrands().first { it["name"] == "HPE" }
        assertEquals("HPE_COMWARE", hpe["vendor"])
    }

    @Test
    @Order(3)
    fun `findMatchingModels matchea por patron y reporta QUE matcheo (error 54)`() {
        importer.importBuiltins()
        val matches = db.catalog.findMatchingModels("Aruba JL255A 2930F-24G-PoE+-4SFPP Switch")
        assertEquals(1, matches.size, "$matches")
        assertEquals("2930F", matches.single().model.name)
        assertTrue(matches.single().captured.isNotBlank(), "el operador ve el string capturado")

        // Error #54: un patrón con anclas no matchea substrings engañosos.
        assertTrue(db.catalog.findMatchingModels("X5130-FAN").isEmpty())
        // Part number del 5140 EI.
        assertEquals("5140 EI", db.catalog.findMatchingModels("HPE JL823A unit").single().model.name)
    }

    @Test
    @Order(4)
    fun `un pack jamas pisa un modelo editado por el operador (error 52)`() {
        importer.importBuiltins()
        val aruba = db.catalog.listBrands().first { it["name"] == "Aruba" }
        val brandId = (aruba["id"] as Number).toLong()

        // El operador edita el 2930F: otra family y métodos recortados.
        val modelId = db.catalog.saveOperatorModel(
            brandId, deviceType = "switch", name = "2930F",
            family = "ArubaOS-Switch-EDITADA", matchPatterns = listOf("(?i)\\b2930F\\b"),
            defaultMethods = listOf("CLI_SSH"),
        )
        assertNotNull(modelId)

        val result = importer.importBuiltins().first { it.brand == "Aruba" }
        assertEquals(1, result.skippedOperator, "el pack reporta el skip, no pisa")
        val model = db.catalog.findModelById(modelId!!)!!
        assertEquals("ArubaOS-Switch-EDITADA", model.family, "la edición del operador sobrevive")
        assertEquals(listOf("CLI_SSH"), model.defaultMethods)
        assertEquals("operator", model.source)
    }

    @Test
    @Order(5)
    fun `pack invalido se rechaza COMPLETO con reporte por modelo y campo (error 53)`() {
        val broken = """
            packVersion: 1
            brand: Rota
            models:
              - name: "OK-1"
                deviceType: switch
                matchPatterns: ["(?i)\\bOK-1\\b"]
                defaultMethods: [CLI_SSH]
              - name: "MALA"
                deviceType: switch
                matchPatterns: ["(?i)[rota"]
                defaultMethods: [CLI_SSH, TELEPATIA]
        """.trimIndent()

        val result = importer.importPack(broken, "rota.yaml")

        assertFalse(result.ok)
        assertTrue(result.errors.any { "MALA" in it && "no compila" in it }, "${result.errors}")
        assertTrue(result.errors.any { "TELEPATIA" in it }, "${result.errors}")
        // Nada se importó: ni siquiera el modelo válido ni la marca.
        assertTrue(db.catalog.listBrands().none { it["name"] == "Rota" }, "rechazo completo, no a medias")
    }

    @Test
    @Order(6)
    fun `pack de marca ficticia importa end-to-end (humo del CI)`() {
        val text = javaClass.getResourceAsStream("/catalog-packs-test/ficticia-lab.yaml")!!
            .bufferedReader().use { it.readText() }
        val result = importer.importPack(text, "ficticia-lab.yaml")
        assertTrue(result.ok, "${result.errors}")
        assertEquals(2, result.created)
        assertEquals("FX-100", db.catalog.findMatchingModels("Ficticia FX-100 rev2").single().model.name)
        assertTrue(importer.importPack(text, "ficticia-lab.yaml").ok, "re-import idempotente")
    }

    @Test
    @Order(7)
    fun `metodos de gestion por dispositivo - opt-in trazable y vinculo al catalogo (error 55)`() {
        importer.importBuiltins()
        val deviceId = db.devices.upsert("sw-cat-01", "10.95.0.1", 22, "SSH",
            com.opentermx.netparsers.Vendor.ARUBA_PROVISION)!!
        val model = db.catalog.listModels().first { it.name == "2930F" }

        assertTrue(db.catalog.assignCatalogModel(deviceId, model.id))
        assertEquals("2930F", db.catalog.catalogModelOf(deviceId)?.name)

        assertTrue(
            db.catalog.setManagementMethod(
                deviceId, "REST_API", enabled = true,
                configJson = """{"baseUrl":"https://10.95.0.1"}""",
                enabledBy = "operador-test",
            )
        )
        val settings = db.catalog.managementSettingsOf(deviceId)
        val rest = settings.single { it["method"] == "REST_API" }
        assertEquals(true, rest["enabled"])
        assertEquals("operador-test", rest["enabled_by"])
        assertNotNull(rest["enabled_at"], "el opt-in registra cuándo")

        // Deshabilitar conserva el rastro de quién lo habilitó originalmente.
        db.catalog.setManagementMethod(deviceId, "REST_API", enabled = false, enabledBy = "operador-test")
        val disabled = db.catalog.managementSettingsOf(deviceId).single { it["method"] == "REST_API" }
        assertEquals(false, disabled["enabled"])
        assertNotNull(disabled["enabled_at"])
    }

    @Test
    @Order(8)
    fun `exportBrandPack produce YAML re-importable (round-trip)`() {
        importer.importBuiltins()
        val exported = importer.exportBrandPack("HPE")
        assertNotNull(exported)
        assertTrue("5130 EI" in exported!! && "hp_comware" in exported, exported)
        val reimport = importer.importPack(exported, "hpe-export.yaml")
        assertTrue(reimport.ok, "${reimport.errors}")
    }

    @Test
    @Order(9)
    fun `device con vendor HPE_COMWARE persiste (roundtrip del enum nuevo)`() {
        val deviceId = db.devices.upsert("sw-comware-01", "10.95.0.2", 22, "SSH",
            com.opentermx.netparsers.Vendor.HPE_COMWARE)
        assertNotNull(deviceId)
        assertEquals("HPE_COMWARE", db.devices.findById(deviceId!!)!!["vendor"])
    }

    private object EmbeddedPg {
        val db: TelemetryDb by lazy {
            val pg = io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().start()
            Runtime.getRuntime().addShutdownHook(Thread { runCatching { pg.close() } })
            TelemetryDb.connect(
                DbConfig(host = "localhost", port = pg.port, database = "postgres", username = "postgres", password = "postgres"),
            ).getOrThrow()
        }
    }
}
