package com.opentermx.mcp.handlers

import com.opentermx.mcp.handlers.McpToolException.ErrorCode.INVALID_ARGUMENT

/**
 * Utilidades chiquitas para extraer y coercer argumentos de un `Map<String, Any?>` con
 * mensajes de error consistentes. JSON-RPC trae enteros como `Int`, `Long` o `Double`
 * según el parser — acá normalizamos a `Int`/`String`/`List<String>` para los handlers.
 */
internal object Args {

    fun requireString(args: Map<String, Any?>, key: String): String {
        val v = args[key] ?: throw McpToolException(INVALID_ARGUMENT, "Falta el argumento requerido `$key`")
        if (v !is String || v.isBlank()) {
            throw McpToolException(INVALID_ARGUMENT, "El argumento `$key` debe ser un string no vacío")
        }
        return v
    }

    fun optionalInt(args: Map<String, Any?>, key: String, default: Int, min: Int, max: Int): Int {
        val raw = args[key] ?: return default
        val value = when (raw) {
            is Int -> raw
            is Long -> raw.toInt()
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
                ?: throw McpToolException(INVALID_ARGUMENT, "`$key` debe ser un entero, vino `$raw`")
            else -> throw McpToolException(INVALID_ARGUMENT, "`$key` debe ser un entero")
        }
        if (value < min || value > max) {
            throw McpToolException(INVALID_ARGUMENT, "`$key` debe estar entre $min y $max (vino $value)")
        }
        return value
    }

    fun requireStringList(args: Map<String, Any?>, key: String): List<String> {
        val raw = args[key] ?: throw McpToolException(INVALID_ARGUMENT, "Falta el argumento requerido `$key`")
        if (raw !is List<*> || raw.isEmpty()) {
            throw McpToolException(INVALID_ARGUMENT, "`$key` debe ser un array no vacío de strings")
        }
        return raw.map {
            (it as? String) ?: throw McpToolException(INVALID_ARGUMENT, "`$key` contiene un elemento no-string")
        }
    }

    fun optionalString(args: Map<String, Any?>, key: String): String? {
        val v = args[key] ?: return null
        return (v as? String)?.takeIf { it.isNotBlank() }
    }
}