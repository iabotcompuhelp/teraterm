package com.opentermx.telemetrydb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contrato del migrador (error #40): cualquier JSONB histórico, corrupto o de versión
 * desconocida produce un perfil mínimo VÁLIDO con todas las claves del shape vigente —
 * jamás una excepción.
 */
class ProfileMigratorTest {

    private fun assertCurrentShape(profile: Map<String, Any?>) {
        assertEquals(
            setOf("capabilities", "uplinks", "notes", "maintenanceWindow", "contact", "custom"),
            profile.keys,
        )
        val caps = profile["capabilities"] as Map<*, *>
        assertEquals(setOf("tools", "readonlyProfile", "forbidden"), caps.keys)
    }

    @Test
    fun `fixture v1 completo migra al shape vigente conservando los valores`() {
        val v1 = """
            {
              "capabilities": {"tools": ["get_interface_stats"], "readonlyProfile": "cisco-default", "forbidden": ["reload"]},
              "uplinks": ["Gi1/0/48"],
              "notes": "switch de acceso piso 2",
              "maintenanceWindow": "sab 02:00-04:00",
              "contact": "noc@empresa.example",
              "custom": {"stackMembers": "C9300-48P,C9300-24UX"}
            }
        """.trimIndent()
        val migrated = ProfileMigrator.migrate(v1, 1)
        assertTrue(migrated.warnings.isEmpty(), "v1 vigente migra sin warnings: ${migrated.warnings}")
        assertCurrentShape(migrated.profile)
        assertEquals("switch de acceso piso 2", migrated.profile["notes"])
        assertEquals(listOf("Gi1/0/48"), migrated.profile["uplinks"])
        val caps = migrated.profile["capabilities"] as Map<*, *>
        assertEquals(listOf("reload"), caps["forbidden"])
        assertEquals("C9300-48P,C9300-24UX", (migrated.profile["custom"] as Map<*, *>)["stackMembers"])
    }

    @Test
    fun `JSONB parcial o vacio se completa con defaults`() {
        val migrated = ProfileMigrator.migrate("""{"notes": "solo una nota"}""", 1)
        assertCurrentShape(migrated.profile)
        assertEquals("solo una nota", migrated.profile["notes"])
        assertEquals(emptyList<String>(), migrated.profile["uplinks"])
        assertNull(migrated.profile["maintenanceWindow"])

        assertCurrentShape(ProfileMigrator.migrate("{}", 1).profile)
        assertCurrentShape(ProfileMigrator.migrate(null, 1).profile)
    }

    @Test
    fun `JSONB corrupto produce perfil minimo con warning, no excepcion (error 40)`() {
        val migrated = ProfileMigrator.migrate("{esto no es json", 1)
        assertCurrentShape(migrated.profile)
        assertTrue(migrated.warnings.any { "corrupto" in it })
    }

    @Test
    fun `claves con tipo inesperado se reemplazan por el default en vez de propagar basura`() {
        val migrated = ProfileMigrator.migrate(
            """{"notes": 42, "uplinks": "no-soy-lista", "capabilities": "tampoco-objeto"}""", 1,
        )
        assertCurrentShape(migrated.profile)
        assertNull(migrated.profile["notes"])
        assertEquals(emptyList<String>(), migrated.profile["uplinks"])
    }

    @Test
    fun `version futura desconocida degrada a perfil minimo con warning`() {
        val migrated = ProfileMigrator.migrate("""{"notes": "de una version futura"}""", 99)
        assertCurrentShape(migrated.profile)
        assertNull(migrated.profile["notes"], "no se interpreta un shape desconocido")
        assertTrue(migrated.warnings.any { "99" in it })
    }
}
