package com.opentermx.mcp.protocol

/**
 * Proveedor de los MCP "resources" — URIs que el cliente puede listar (`resources/list`)
 * y leer (`resources/read`). El servidor de OpenTermX expone:
 *
 *  - `opentermx://audit-log` — entradas del audit log (con redacción aplicada).
 *  - `opentermx://sessions`  — snapshot de las sesiones activas.
 *
 * Para mantener al dispatcher independiente de los handlers/audit, la implementación real
 * la inyecta el [com.opentermx.mcp.McpServer] desde `McpServerManager`.
 */
interface ResourceProvider {

    /** Devuelve la lista de resources expuestos. */
    fun list(): List<ResourceDescriptor>

    /** Devuelve el contenido del resource [uri] o `null` si no existe. */
    fun read(uri: String): ResourceContent?

    object Empty : ResourceProvider {
        override fun list(): List<ResourceDescriptor> = emptyList()
        override fun read(uri: String): ResourceContent? = null
    }
}

data class ResourceDescriptor(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String = "application/json",
)

data class ResourceContent(
    val uri: String,
    val text: String,
    val mimeType: String = "application/json",
)

/**
 * Provee los MCP "prompts" — templates parametrizables que el cliente puede ofrecer al
 * usuario. Por defecto exponemos dos: `diagnose_connectivity` y `audit_recent_changes`.
 */
interface PromptProvider {

    fun list(): List<PromptDescriptor>

    /**
     * Materializa el prompt [name] con los [arguments] dados. Devuelve la lista de mensajes
     * que el cliente debe ofrecer al usuario (típicamente uno solo con role=user).
     */
    fun get(name: String, arguments: Map<String, Any?>): PromptMaterialized?

    object Default : PromptProvider {
        override fun list(): List<PromptDescriptor> = listOf(
            PromptDescriptor(
                name = "diagnose_connectivity",
                description = "Diagnostica conectividad de una sesión activa de OpenTermX.",
                arguments = listOf(
                    PromptArgument("sessionId", "ID de la sesión a diagnosticar.", required = true),
                ),
            ),
            PromptDescriptor(
                name = "audit_recent_changes",
                description = "Resume los cambios de configuración recientes del audit log.",
                arguments = listOf(
                    PromptArgument("hours", "Ventana de tiempo en horas.", required = true),
                ),
            ),
        )

        override fun get(name: String, arguments: Map<String, Any?>): PromptMaterialized? = when (name) {
            "diagnose_connectivity" -> {
                val sid = arguments["sessionId"] as? String ?: return null
                PromptMaterialized(
                    description = "Diagnostica conectividad de la sesión `$sid`.",
                    messages = listOf(
                        PromptMessage(
                            role = "user",
                            text = "Diagnostica la conectividad de la sesión `$sid` en OpenTermX. " +
                                "Usá `inspect_session` para ver los últimos comandos y el output, " +
                                "y `propose_commands` para sugerir tests (ping, traceroute, show interface).",
                        )
                    ),
                )
            }
            "audit_recent_changes" -> {
                val hours = (arguments["hours"] as? Number)?.toInt() ?: 24
                PromptMaterialized(
                    description = "Resume cambios de las últimas $hours horas.",
                    messages = listOf(
                        PromptMessage(
                            role = "user",
                            text = "Usá `read_audit_log` con `sinceMillis=" +
                                "${System.currentTimeMillis() - hours.toLong() * 3_600_000L}` " +
                                "y resumime: qué se ejecutó, qué se rechazó, qué tenía riesgo DANGEROUS.",
                        )
                    ),
                )
            }
            else -> null
        }
    }
}

data class PromptDescriptor(
    val name: String,
    val description: String,
    val arguments: List<PromptArgument> = emptyList(),
)

data class PromptArgument(
    val name: String,
    val description: String,
    val required: Boolean = false,
)

data class PromptMaterialized(
    val description: String,
    val messages: List<PromptMessage>,
)

data class PromptMessage(
    val role: String,
    val text: String,
) {
    fun toMcp(): Map<String, Any?> = linkedMapOf(
        "role" to role,
        "content" to linkedMapOf("type" to "text", "text" to text),
    )
}