package com.opentermx.mcp.inventory

/**
 * Phase 3 Fase 2 — Device Registry.
 *
 * Abstrae el inventario de devices para que `mcp-server` no dependa de `:app` (donde
 * vive `SavedConnections`). El módulo `app` provee una impl concreta
 * (`SettingsInventoryProvider`) en el bootstrap del [com.opentermx.mcp.McpServer].
 *
 * Invariante: **las credenciales NUNCA salen del provider**. Los DTOs que esta
 * interface devuelve son inertes — solo metadata para que el LLM pueda elegir un device
 * por alias/tag/group sin ver passwords. La resolución real de credenciales sucede
 * dentro del `SessionOpener` cuando el cliente invoca `open_session(deviceAlias=…)`.
 */
interface InventoryProvider {

    /**
     * Lista devices del inventario aplicando filtros AND. Cada filtro es ANY-of dentro
     * de sí: con que la entrada tenga uno de los `tags` listados ya pasa.
     */
    fun list(tagsAny: List<String>? = null, groupsAny: List<String>? = null, deviceType: String? = null): List<InventoryDevice>

    /** Resolución por alias exacta. Devuelve null si no existe. */
    fun byAlias(alias: String): InventoryDevice?

    object Empty : InventoryProvider {
        override fun list(tagsAny: List<String>?, groupsAny: List<String>?, deviceType: String?): List<InventoryDevice> =
            emptyList()

        override fun byAlias(alias: String): InventoryDevice? = null
    }
}

/**
 * Vista MCP-safe de una entrada del inventario. Sin credenciales — el `secretRef` es un
 * handle opaco que el `SessionOpener` puede usar para pedirle al credentialStore que
 * descifre, pero el handle en sí no contiene material sensible.
 */
data class InventoryDevice(
    val alias: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val username: String,
    val deviceType: String?,
    val tags: List<String>,
    val groups: List<String>,
    /** ID de la SavedConnection original; sirve como `credentialRef` para `open_session`. */
    val savedConnectionId: String,
    /** Texto descriptivo cuando lo hay (label o user@host:port). */
    val displayLabel: String,
)
