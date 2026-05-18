package com.opentermx.mcp.operation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.everit.json.schema.Schema
import org.everit.json.schema.ValidationException
import org.everit.json.schema.loader.SchemaLoader
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.file.Files
import java.nio.file.Path

/**
 * Carga y valida una [OperationContext] desde:
 *  - un YAML/JSON en disco (`fromPath`),
 *  - un mapa ya deserializado (`fromInline`, usado por `start_operation` con `contextInline`),
 *  - una string YAML (`fromYamlString`, usado en tests).
 *
 * La validación corre contra el JSON Schema en `resources/schemas/operation-context.schema.json`
 * usando `everit-json-schema`. Si falla, lanza [OperationContextException] con un mensaje
 * accionable que enumera las violaciones (no un stack trace).
 */
object OperationContextLoader {

    private const val SCHEMA_RESOURCE = "/schemas/operation-context.schema.json"

    private val jsonMapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val yamlMapper: ObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    private val schema: Schema by lazy {
        val stream = OperationContextLoader::class.java.getResourceAsStream(SCHEMA_RESOURCE)
            ?: error("Schema de operation context no encontrado en classpath ($SCHEMA_RESOURCE)")
        stream.use {
            val raw = JSONObject(JSONTokener(it))
            SchemaLoader.builder().schemaJson(raw).draftV7Support().build().load().build()
        }
    }

    fun fromPath(path: Path): OperationContext {
        if (!Files.isRegularFile(path)) {
            throw OperationContextException("No existe el archivo `$path`")
        }
        val rawText = Files.readString(path, Charsets.UTF_8)
        val isYaml = path.fileName.toString().lowercase().let { it.endsWith(".yaml") || it.endsWith(".yml") }
        return parseAndValidate(rawText, if (isYaml) Format.YAML else Format.JSON)
    }

    fun fromYamlString(yaml: String): OperationContext = parseAndValidate(yaml, Format.YAML)

    fun fromJsonString(json: String): OperationContext = parseAndValidate(json, Format.JSON)

    /**
     * Útil cuando el cliente MCP pasa `contextInline` como mapa JSON en `tools/call.arguments`.
     * Re-serializamos a JSON canónico antes de validar para que everit reciba siempre lo mismo
     * que validó en los tests de schema.
     */
    fun fromInline(inline: Map<String, Any?>): OperationContext {
        val json = jsonMapper.writeValueAsString(inline)
        return parseAndValidate(json, Format.JSON)
    }

    private enum class Format { YAML, JSON }

    private fun parseAndValidate(raw: String, format: Format): OperationContext {
        val canonicalJson = when (format) {
            Format.YAML -> jsonMapper.writeValueAsString(yamlMapper.readTree(raw))
            Format.JSON -> jsonMapper.writeValueAsString(jsonMapper.readTree(raw))
        }
        try {
            schema.validate(JSONObject(canonicalJson))
        } catch (e: ValidationException) {
            throw OperationContextException(formatValidationError(e))
        }
        return jsonMapper.readValue(canonicalJson, OperationContext::class.java)
    }

    /**
     * Aplana las violaciones que reporta everit en un solo string legible. Sin esto el
     * mensaje raíz dice "#: 2 schema violations found" sin decir cuáles.
     */
    private fun formatValidationError(e: ValidationException): String {
        val all = e.allMessages.takeIf { it.isNotEmpty() } ?: listOf(e.message ?: "schema violation")
        return "Operation context inválido:\n" + all.joinToString("\n") { "  - $it" }
    }
}

class OperationContextException(message: String) : RuntimeException(message)
