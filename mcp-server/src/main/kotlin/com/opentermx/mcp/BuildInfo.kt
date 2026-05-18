package com.opentermx.mcp

import java.util.Properties

/**
 * Resuelve la versión del módulo `mcp-server` en runtime. El default histórico era
 * `"0.1.0"` hardcoded en los constructores de [McpServer] y [com.opentermx.mcp.protocol.McpDispatcher],
 * que sobrevivió al bump a 1.0.0 (T18 de Phase 2). Phase 2.5 T1 lo reemplaza por una
 * resolución dinámica con tres niveles de fallback:
 *
 *  1. `Package.getImplementationVersion()` — funciona si la clase se carga desde un JAR
 *     cuyo manifest tiene `Implementation-Version` (configurado en `build.gradle.kts`).
 *  2. Resource classpath `com/opentermx/mcp/build-info.properties` — generado por la
 *     tarea Gradle `generateBuildInfo`; siempre disponible incluso corriendo desde
 *     `build/classes/` (tests, `:app:run`).
 *  3. `"unknown"` — NO `"0.1.0"`, para que sea evidente si los dos pasos anteriores fallan.
 */
object BuildInfo {

    val VERSION: String by lazy { readVersion() }

    private fun readVersion(): String {
        BuildInfo::class.java.`package`?.implementationVersion
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        BuildInfo::class.java.getResourceAsStream("/com/opentermx/mcp/build-info.properties")?.use { stream ->
            val props = Properties().apply { load(stream) }
            props.getProperty("version")?.takeIf { it.isNotBlank() }?.let { return it }
        }

        return "unknown"
    }
}
