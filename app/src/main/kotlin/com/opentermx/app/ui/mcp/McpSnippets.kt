package com.opentermx.app.ui.mcp

import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path

/**
 * Genera snippets de configuración para clientes MCP (Claude Desktop, Cursor, Cline) que
 * tienen formato JSON casi idéntico pero viven en paths distintos por OS. También resuelve
 * la ruta del config file para que el botón "Open config folder" abra el directorio
 * correcto en el file manager nativo.
 *
 * No persiste los snippets en disco; solo arma el texto que la UI muestra y copia al
 * portapapeles cuando el operador lo decide.
 */
object McpSnippets {

    enum class Client { CLAUDE_DESKTOP, CURSOR, CLINE }
    enum class OS { MAC, WINDOWS, LINUX }

    data class Bundle(
        val client: Client,
        val configPath: Path,
        val snippet: String,
    )

    fun detectOs(): OS {
        val name = System.getProperty("os.name").lowercase()
        return when {
            name.contains("win") -> OS.WINDOWS
            name.contains("mac") || name.contains("darwin") -> OS.MAC
            else -> OS.LINUX
        }
    }

    fun configPathFor(client: Client, os: OS = detectOs()): Path {
        val home = Path.of(System.getProperty("user.home"))
        return when (client) {
            Client.CLAUDE_DESKTOP -> when (os) {
                OS.MAC -> home.resolve("Library/Application Support/Claude/claude_desktop_config.json")
                OS.WINDOWS -> Path.of(System.getenv("APPDATA") ?: home.toString()).resolve("Claude/claude_desktop_config.json")
                OS.LINUX -> home.resolve(".config/Claude/claude_desktop_config.json")
            }
            Client.CURSOR -> home.resolve(".cursor/mcp.json")
            Client.CLINE -> when (os) {
                OS.MAC -> home.resolve("Library/Application Support/Code/User/globalStorage/saoudrizwan.claude-dev/settings/cline_mcp_settings.json")
                OS.WINDOWS -> Path.of(System.getenv("APPDATA") ?: home.toString())
                    .resolve("Code/User/globalStorage/saoudrizwan.claude-dev/settings/cline_mcp_settings.json")
                OS.LINUX -> home.resolve(".config/Code/User/globalStorage/saoudrizwan.claude-dev/settings/cline_mcp_settings.json")
            }
        }
    }

    /**
     * Construye el snippet HTTP. [token] puede ser null/vacío si el servidor no requiere auth.
     * [stdioWrapperPath] activa el formato stdio si el proxy está instalado.
     */
    fun bundle(
        client: Client,
        host: String,
        port: Int,
        token: String?,
        stdioWrapperPath: String? = null,
    ): Bundle {
        val configPath = configPathFor(client)
        val snippet = if (stdioWrapperPath != null) {
            stdioSnippet(stdioWrapperPath)
        } else {
            httpSnippet(host, port, token)
        }
        return Bundle(client, configPath, snippet)
    }

    private fun httpSnippet(host: String, port: Int, token: String?): String {
        val headersLine = if (!token.isNullOrBlank()) {
            ",\n      \"headers\": {\"Authorization\": \"Bearer $token\"}"
        } else ""
        return """
            |{
            |  "mcpServers": {
            |    "opentermx": {
            |      "url": "http://$host:$port/mcp"$headersLine
            |    }
            |  }
            |}
        """.trimMargin()
    }

    private fun stdioSnippet(wrapperPath: String): String = """
        |{
        |  "mcpServers": {
        |    "opentermx": {
        |      "command": "$wrapperPath"
        |    }
        |  }
        |}
    """.trimMargin()

    /** Intenta abrir el directorio padre del config en el file manager del OS. */
    fun openConfigFolder(client: Client): Boolean {
        val path = configPathFor(client).parent
        return runCatching {
            Files.createDirectories(path)
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(path.toFile())
                true
            } else false
        }.getOrDefault(false)
    }
}