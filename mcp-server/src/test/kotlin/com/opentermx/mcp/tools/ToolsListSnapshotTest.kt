package com.opentermx.mcp.tools

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Snapshot del catálogo `tools/list` (recomendación de compatibilidad de la Fase 5):
 * cualquier cambio de nombre, flag `mutating` o schema de una tool queda VISIBLE en el
 * diff del PR vía `tools-list.snapshot.json` (hash por tool, no el schema completo).
 *
 * Si el snapshot quedó desactualizado a propósito (agregaste una tool o cambiaste un
 * schema deliberadamente): borrá `src/test/resources/tools-list.snapshot.json`, corré el
 * test (lo regenera y falla avisando) y commiteá el archivo nuevo junto con el cambio.
 */
class ToolsListSnapshotTest {

    private val mapper: ObjectMapper = jacksonObjectMapper()

    private fun signature(): Map<String, Map<String, Any?>> =
        ToolDefinitions.ALL.associate { tool ->
            tool.name to mapOf(
                "mutating" to tool.mutating,
                "inputSha256" to sha256(mapper.writeValueAsString(tool.inputSchema)),
                "outputSha256" to sha256(mapper.writeValueAsString(tool.outputSchema)),
            )
        }

    @Test
    fun `el catalogo de tools coincide con el snapshot commiteado`() {
        val actual = signature()
        if (!Files.isRegularFile(SNAPSHOT_PATH)) {
            Files.createDirectories(SNAPSHOT_PATH.parent)
            Files.writeString(
                SNAPSHOT_PATH,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual),
            )
            fail<Unit>(
                "No existía $SNAPSHOT_PATH — lo generé con el catálogo actual " +
                    "(${actual.size} tools). Revisalo y commitealo.",
            )
        }
        @Suppress("UNCHECKED_CAST")
        val expected = mapper.readValue(Files.readString(SNAPSHOT_PATH), Map::class.java) as Map<String, Any?>

        assertEquals(
            expected.keys.sorted(), actual.keys.sorted(),
            "El SET de tools cambió. Si es intencional, regenerá el snapshot (ver KDoc).",
        )
        actual.forEach { (name, sig) ->
            assertEquals(
                expected[name], sig,
                "El schema o el flag mutating de `$name` cambió. Los schemas publicados son " +
                    "contrato con los clientes MCP — si el cambio es intencional, regenerá el snapshot.",
            )
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        // Working dir de los tests Gradle = directorio del módulo.
        val SNAPSHOT_PATH: Path = Path.of("src", "test", "resources", "tools-list.snapshot.json")
    }
}
