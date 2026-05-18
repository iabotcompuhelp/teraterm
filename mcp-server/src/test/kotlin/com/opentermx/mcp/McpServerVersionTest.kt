package com.opentermx.mcp

import com.opentermx.mcp.protocol.JsonRpcRequest
import com.opentermx.mcp.protocol.McpDispatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regresión de Phase 2.5 T1: el default hardcoded `"0.1.0"` sobrevivió al bump a 1.0.0,
 * de modo que `/mcp/health` y el resultado de `initialize` reportaban una versión obsoleta.
 * Validamos que:
 *  - `BuildInfo.VERSION` matchea semver `MAJOR.MINOR.PATCH`.
 *  - `BuildInfo.VERSION` NO es `"0.1.0"` (el valor que originaba el bug).
 *  - El default del constructor de `McpDispatcher` propaga la versión real al
 *    payload de `initialize.result.serverInfo.version`.
 */
class McpServerVersionTest {

    private val semverRegex = Regex("""^\d+\.\d+\.\d+(?:[-+].+)?$""")

    @Test
    fun `BuildInfo VERSION matchea semver`() {
        val v = BuildInfo.VERSION
        assertTrue(semverRegex.matches(v), "VERSION='$v' no matchea semver MAJOR.MINOR.PATCH")
    }

    @Test
    fun `BuildInfo VERSION no es el legacy 0_1_0`() {
        assertNotEquals("0.1.0", BuildInfo.VERSION, "el bump T18 a 1.0.0 quedó sin propagarse al runtime")
    }

    @Test
    fun `BuildInfo VERSION no es unknown — el fallback debería estar cubierto por el resource generado`() {
        // Si esto falla, el task `generateBuildInfo` del build.gradle.kts no corrió o el
        // resource no está en el classpath de tests. Apunta a un problema de build, no a
        // un bug en producción — pero el síntoma sería igualmente confuso para el operador.
        assertNotEquals("unknown", BuildInfo.VERSION, "BuildInfo no resolvió la versión; resource ausente del classpath")
    }

    @Test
    fun `default del McpDispatcher reporta la version real en initialize`() {
        val dispatcher = McpDispatcher(handlers = emptyList())
        val response = dispatcher.handle(JsonRpcRequest(id = 1, method = "initialize"))!!
        @Suppress("UNCHECKED_CAST")
        val result = response.result as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val serverInfo = result["serverInfo"] as Map<String, Any?>
        val version = serverInfo["version"] as? String
        assertNotNull(version, "serverInfo.version no debería ser null")
        assertNotEquals("0.1.0", version)
        assertEquals(BuildInfo.VERSION, version)
    }
}
