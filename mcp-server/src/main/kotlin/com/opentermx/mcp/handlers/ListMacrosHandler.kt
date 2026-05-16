package com.opentermx.mcp.handlers

import com.opentermx.macro.MacroRegistry
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions

/**
 * Handler de la tool `list_macros`. Lee la carpeta `~/.opentermx/macros/` (o la pasada
 * explícitamente) y devuelve la lista con sus metadatos parseados de los headers `//!`.
 */
class ListMacrosHandler(
    private val registry: MacroRegistry = MacroRegistry(),
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.LIST_MACROS

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val macros = registry.list().map { info ->
            linkedMapOf<String, Any?>(
                "name" to info.name(),
                "description" to info.description(),
                "parameters" to info.parameters().map { p ->
                    linkedMapOf(
                        "name" to p.name(),
                        "type" to p.type(),
                        "required" to p.required(),
                        "description" to p.description(),
                    )
                },
            )
        }
        return linkedMapOf("macros" to macros)
    }
}