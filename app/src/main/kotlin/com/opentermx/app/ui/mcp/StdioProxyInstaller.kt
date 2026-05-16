package com.opentermx.app.ui.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.opentermx.app.settings.AiAssistantSettings
import com.opentermx.common.crypto.EncryptedValue
import com.opentermx.common.crypto.SecretCipher
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Instala el wrapper del [com.opentermx.mcp.StdioProxyMain] en `~/.opentermx/bin/` y
 * escribe el archivo de config `~/.opentermx/mcp-stdio.json` con `url + token +
 * protocolVersion` que el proxy lee al arrancar.
 *
 * Es equivalente a la task Gradle `:app:installStdioProxy` pero ejecutable desde la UI
 * en runtime — el classpath y la ruta a `java` se descubren del proceso actual, así que
 * funciona tanto en `./gradlew run` como en una instalación jpackage.
 */
object StdioProxyInstaller {

    private val mapper: ObjectMapper = jacksonObjectMapper()

    data class InstallResult(val configFile: Path, val wrapperFile: Path)

    fun install(settings: AiAssistantSettings): InstallResult {
        val home = Path.of(System.getProperty("user.home"))
        val configDir = home.resolve(".opentermx")
        val binDir = configDir.resolve("bin")
        Files.createDirectories(binDir)

        val configFile = configDir.resolve("mcp-stdio.json")
        val url = "http://${settings.mcpServerBindAddress}:${settings.mcpServerPort}/mcp"
        val token = decodeToken(settings.mcpServerToken).orEmpty()
        val configPayload = linkedMapOf<String, Any?>(
            "url" to url,
            "token" to token.ifBlank { null },
            // Se actualizará dinámicamente tras initialize; el default cubre el primer arranque.
            "protocolVersion" to "2024-11-05",
        )
        Files.writeString(configFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(configPayload))

        val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val javaExe = Path.of(
            System.getProperty("java.home"),
            "bin",
            if (isWindows) "java.exe" else "java",
        ).toString()
        val classpath = System.getProperty("java.class.path")
        val wrapperFile = if (isWindows) {
            binDir.resolve("opentermx-mcp-stdio.bat")
        } else {
            binDir.resolve("opentermx-mcp-stdio")
        }
        val wrapperBody = if (isWindows) {
            """
            |@echo off
            |REM Wrapper autogenerado por OpenTermX (Setup → AI Assistant → MCP Server).
            |REM Reenvía stdio JSON-RPC al servidor HTTP local.
            |"$javaExe" -cp "$classpath" com.opentermx.mcp.StdioProxyMain %*
            """.trimMargin()
        } else {
            """
            |#!/usr/bin/env bash
            |# Wrapper autogenerado por OpenTermX (Setup → AI Assistant → MCP Server).
            |# Reenvía stdio JSON-RPC al servidor HTTP local.
            |exec "$javaExe" -cp "$classpath" com.opentermx.mcp.StdioProxyMain "$@"
            """.trimMargin()
        }
        Files.writeString(wrapperFile, wrapperBody)
        if (!isWindows) {
            runCatching {
                Files.setPosixFilePermissions(
                    wrapperFile,
                    PosixFilePermissions.fromString("rwxr-xr-x"),
                )
            }
        }
        return InstallResult(configFile = configFile, wrapperFile = wrapperFile)
    }

    fun uninstall() {
        val home = Path.of(System.getProperty("user.home"))
        val binDir = home.resolve(".opentermx").resolve("bin")
        for (name in listOf("opentermx-mcp-stdio", "opentermx-mcp-stdio.bat")) {
            runCatching { Files.deleteIfExists(binDir.resolve(name)) }
        }
    }

    private fun decodeToken(value: EncryptedValue?): String? {
        if (value == null || EncryptedValue.isEmpty(value)) return null
        return runCatching { SecretCipher.decrypt(value).takeIf { it.isNotBlank() } }.getOrNull()
    }
}