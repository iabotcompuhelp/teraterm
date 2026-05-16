package com.opentermx.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Proxy stdio → HTTP del servidor MCP de OpenTermX.
 *
 * Algunos clientes MCP (Claude Desktop en versiones viejas, configuraciones empresariales,
 * IDEs con sandbox) sólo soportan transporte stdio. En esos casos, el operador instala
 * este binario como `command` y este proxy reenvía cada JSON-RPC line de stdin al servidor
 * HTTP local que la GUI de OpenTermX está corriendo. El `SessionRegistry` vive en el
 * proceso de la GUI; este proxy es deliberadamente thin: no replica nada.
 *
 * Config en `~/.opentermx/mcp-stdio.json`:
 * ```json
 * {
 *   "url": "http://127.0.0.1:8765/mcp",
 *   "token": "...",            // opcional
 *   "protocolVersion": "2024-11-05"  // opcional, se actualiza tras initialize
 * }
 * ```
 * La UI de OpenTermX genera este archivo cuando el operador marca "Enable stdio proxy".
 *
 * Errores van a stderr (los clientes MCP los muestran al usuario sin contaminar el canal
 * JSON-RPC). Si la GUI no está corriendo, cada request devuelve un JSON-RPC error claro.
 */
object StdioProxyMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val mapper: ObjectMapper = jacksonObjectMapper()
        val configFile = Path.of(System.getProperty("user.home"), ".opentermx", "mcp-stdio.json")
        if (!Files.isRegularFile(configFile)) {
            System.err.println(
                "[opentermx-mcp-stdio] No encuentro $configFile. " +
                    "Habilita el stdio proxy desde Setup → AI Assistant → MCP Server."
            )
            kotlin.system.exitProcess(2)
        }

        @Suppress("UNCHECKED_CAST")
        val config = mapper.readValue(configFile.toFile(), Map::class.java) as Map<String, Any?>
        val url = (config["url"] as? String) ?: "http://127.0.0.1:8765/mcp"
        val token = config["token"] as? String
        // Versión negociada — se actualiza dinámicamente tras initialize.
        var protocolVersion = config["protocolVersion"] as? String

        val httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        val reader = System.`in`.bufferedReader()
        val out = System.out.bufferedWriter()

        System.err.println("[opentermx-mcp-stdio] proxy listo → $url (token=${if (token.isNullOrBlank()) "off" else "on"})")

        while (true) {
            val line = try {
                reader.readLine() ?: break
            } catch (e: Throwable) {
                System.err.println("[opentermx-mcp-stdio] EOF/IO: ${e.message}")
                break
            }
            if (line.isBlank()) continue

            val responseBody = try {
                val reqBuilder = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                if (!token.isNullOrBlank()) reqBuilder.header("Authorization", "Bearer $token")
                if (!protocolVersion.isNullOrBlank() && !line.contains("\"method\":\"initialize\"")) {
                    reqBuilder.header("MCP-Protocol-Version", protocolVersion)
                }
                val req = reqBuilder.POST(HttpRequest.BodyPublishers.ofString(line)).build()
                val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
                handleInitializeResponse(mapper, line, resp.body())?.let { protocolVersion = it }
                resp.body()
            } catch (e: Throwable) {
                // Devolvemos un JSON-RPC error sintetico para no romper el client.
                System.err.println("[opentermx-mcp-stdio] error contactando $url: ${e.message}")
                """{"jsonrpc":"2.0","id":null,"error":{"code":-32603,"message":"Proxy MCP no pudo contactar al servidor: ${e.message?.replace("\"", "'")}"}}"""
            }

            // El cliente espera una línea por response.
            out.write(responseBody)
            out.newLine()
            out.flush()
        }
        System.err.println("[opentermx-mcp-stdio] stdin cerrado, terminando")
    }

    /**
     * Si la línea de request fue `initialize`, parseá la respuesta y devolvé la versión
     * negociada. Si no, devolvé null. Tolera errores de parse silenciosamente.
     */
    private fun handleInitializeResponse(mapper: ObjectMapper, requestLine: String, responseBody: String): String? {
        if (!requestLine.contains("\"method\":\"initialize\"")) return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val parsed = mapper.readValue(responseBody, Map::class.java) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val result = parsed["result"] as? Map<String, Any?> ?: return@runCatching null
            result["protocolVersion"] as? String
        }.getOrNull()
    }
}