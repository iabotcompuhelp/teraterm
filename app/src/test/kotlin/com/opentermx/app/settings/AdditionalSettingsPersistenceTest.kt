package com.opentermx.app.settings

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifica que el motor VT elegido en Setup → Additional sobrevive al ciclo
 * `escribir settings.json → reabrir el dialog`. Es el camino real: SettingsStore
 * serializa AppSettings a JSON al cerrar el dialog y `AdditionalSettingsDialog`
 * vuelve a sembrar su combo desde `settings.additional` cargado de disco.
 */
class AdditionalSettingsPersistenceTest {

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `terminalEngine NATIVE persists across JSON roundtrip`() {
        val original = AppSettings(
            additional = AdditionalSettings(terminalEngine = "NATIVE"),
        )

        val json = mapper.writeValueAsString(original)
        val restored = mapper.readValue(json, AppSettings::class.java)

        assertEquals("NATIVE", restored.additional.terminalEngine)
    }

    @Test
    fun `legacy JSON without terminalEngine defaults to KOTLIN`() {
        // Simula un settings.json escrito antes de añadir el campo: Jackson debe
        // rellenar el default sin romper la lectura.
        val legacyJson = """{"additional":{"serialBackend":"NATIVE"}}"""

        val restored = mapper.readValue(legacyJson, AppSettings::class.java)

        assertEquals("KOTLIN", restored.additional.terminalEngine)
        assertEquals("NATIVE", restored.additional.serialBackend)
    }
}