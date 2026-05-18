package com.opentermx.policy

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
 * Carga y valida policies desde YAML/JSON. La validación contra el JSON Schema usa
 * `everit-json-schema` (Draft-07), mismo motor que el resto del proyecto.
 */
object PolicyLoader {

    private const val SCHEMA_RESOURCE = "/schemas/policy.schema.json"

    private val jsonMapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val yamlMapper: ObjectMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    private val schema: Schema by lazy {
        val stream = PolicyLoader::class.java.getResourceAsStream(SCHEMA_RESOURCE)
            ?: error("Schema de policy no encontrado en classpath ($SCHEMA_RESOURCE)")
        stream.use {
            val raw = JSONObject(JSONTokener(it))
            SchemaLoader.builder().schemaJson(raw).draftV7Support().build().load().build()
        }
    }

    fun fromPath(path: Path): Policy {
        if (!Files.isRegularFile(path)) {
            throw PolicyException("No existe el archivo `$path`")
        }
        val raw = Files.readString(path, Charsets.UTF_8)
        val isYaml = path.fileName.toString().lowercase().let { it.endsWith(".yaml") || it.endsWith(".yml") }
        return parseAndValidate(raw, isYaml)
    }

    fun fromYamlString(yaml: String): Policy = parseAndValidate(yaml, isYaml = true)
    fun fromJsonString(json: String): Policy = parseAndValidate(json, isYaml = false)

    private fun parseAndValidate(raw: String, isYaml: Boolean): Policy {
        val canonicalJson = if (isYaml) {
            jsonMapper.writeValueAsString(yamlMapper.readTree(raw))
        } else {
            jsonMapper.writeValueAsString(jsonMapper.readTree(raw))
        }
        try {
            schema.validate(JSONObject(canonicalJson))
        } catch (e: ValidationException) {
            throw PolicyException(formatValidationError(e))
        }
        return jsonMapper.readValue(canonicalJson, Policy::class.java)
    }

    private fun formatValidationError(e: ValidationException): String {
        val all = e.allMessages.takeIf { it.isNotEmpty() } ?: listOf(e.message ?: "schema violation")
        return "Policy inválida:\n" + all.joinToString("\n") { "  - $it" }
    }
}

class PolicyException(message: String) : RuntimeException(message)
