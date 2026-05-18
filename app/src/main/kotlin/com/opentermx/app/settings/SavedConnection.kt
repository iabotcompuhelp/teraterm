package com.opentermx.app.settings

import com.fasterxml.jackson.annotation.JsonInclude
import com.opentermx.common.crypto.EncryptedValue
import com.opentermx.common.crypto.SecretCipher

enum class SavedAuthKind { NONE, PASSWORD, SSH_KEY }

/**
 * Credenciales guardadas para una conexión previa, persistidas en `AppSettings.savedConnections`.
 *
 * El `secret` (password para `PASSWORD`, o passphrase para `SSH_KEY`) está cifrado con
 * [SecretCipher] (AES-256-GCM con clave derivada de `user::host::home` del SO). Es protección
 * contra inspección casual del filesystem, no contra un adversario con acceso completo a la
 * máquina — el caveat de SecretCipher aplica también acá.
 *
 * `lastUsedAtMillis` permite ordenar las entradas por uso reciente cuando hay varias para el
 * mismo `(host, port)` (ej. diferentes usuarios contra el mismo bastión).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SavedConnection(
    val id: String,
    val protocol: String,                   // por ahora siempre "SSH"
    val host: String,
    val port: Int,
    val username: String,
    val authKind: SavedAuthKind = SavedAuthKind.NONE,
    val secret: EncryptedValue? = null,     // password o passphrase (segun authKind)
    val keyPath: String? = null,            // para SSH_KEY
    val lastUsedAtMillis: Long = System.currentTimeMillis(),
    /**
     * Nombre amigable definido por el operador (ej. "Router Core", "Switch Piso 3"). Vacío
     * por defecto. Cuando está presente, se usa como título del tab y en el listado del
     * `SavedConnectionsDialog` en lugar de `user@host:port`. Editable inline.
     */
    val label: String = "",
    /**
     * Phase 3 Fase 2 — Device Registry. Cuatro campos opcionales que convierten una
     * SavedConnection en una entrada de inventario consumible por las tools MCP
     * `inventory_list` / `inventory_describe` y por `open_session` cuando recibe
     * `deviceAlias`. Settings persistidos antes de Fase 2 quedan con todos null/empty;
     * Jackson respeta lo que ya hay en `~/.opentermx/settings.json`.
     *
     * - [alias]: nombre lógico único (ej. `core-router-1`). El LLM lo usa para referirse
     *   al device sin pasar host/port/credentials. Único globalmente; resolución O(1)
     *   vía [SavedConnections.aliasIndex].
     * - [tags]: etiquetas free-form para filtrar (`["core", "lab", "site-a"]`).
     * - [groups]: agrupaciones (`["cores", "ospf-area-0"]`). Mismo contrato que tags,
     *   separados para que la UI pueda mostrarlos distinto si conviene.
     * - [deviceType]: valor recomendado (`cisco_ios`, `huawei_vrp`, `hpe_comware`,
     *   `fortinet`, `linux_shell`, etc.) usado por Fase 5 para matchear policies. Se
     *   mantiene como `String` libre — no enum — para no forzar migraciones cuando
     *   aparezcan vendors nuevos.
     */
    val alias: String? = null,
    val tags: List<String> = emptyList(),
    val groups: List<String> = emptyList(),
    val deviceType: String? = null,
) {
    /** Texto descriptivo para UI: usa `label` si está, sino cae al user@host:port clásico. */
    fun displayLabel(): String = if (label.isNotBlank()) label else "$username@$host:$port"
}

object SavedConnections {

    /**
     * Devuelve la entrada más reciente que matchea `(protocol, host, port)`, o `null` si no hay.
     * Pensado para autofill del dialog al abrir New Connection.
     */
    fun findMostRecent(
        entries: List<SavedConnection>,
        protocol: String,
        host: String,
        port: Int,
    ): SavedConnection? =
        entries
            .filter { it.protocol == protocol && it.host.equals(host, ignoreCase = true) && it.port == port }
            .maxByOrNull { it.lastUsedAtMillis }

    /**
     * Inserta o reemplaza una entrada con la misma `(protocol, host, port, username)`. Devuelve
     * la lista actualizada (no muta `entries`). `bumpLastUsed = true` actualiza `lastUsedAtMillis`
     * a `now`.
     */
    fun upsert(
        entries: List<SavedConnection>,
        candidate: SavedConnection,
        bumpLastUsed: Boolean = true,
    ): List<SavedConnection> {
        val withFreshTimestamp = if (bumpLastUsed) candidate.copy(lastUsedAtMillis = System.currentTimeMillis()) else candidate
        val filtered = entries.filterNot {
            it.protocol == candidate.protocol &&
                it.host.equals(candidate.host, ignoreCase = true) &&
                it.port == candidate.port &&
                it.username == candidate.username
        }
        return filtered + withFreshTimestamp
    }

    /** Quita la entrada con el `id` dado. */
    fun removeById(entries: List<SavedConnection>, id: String): List<SavedConnection> =
        entries.filterNot { it.id == id }

    /** Quita las entradas que matcheen `(protocol, host, port)` — útil al destildar "Recordar". */
    fun removeByHost(
        entries: List<SavedConnection>,
        protocol: String,
        host: String,
        port: Int,
    ): List<SavedConnection> =
        entries.filterNot {
            it.protocol == protocol && it.host.equals(host, ignoreCase = true) && it.port == port
        }

    /**
     * Phase 3 Fase 2 — Device Registry helpers.
     *
     * Resolución O(1) por alias. Devuelve null si el alias no existe o si más de una
     * entrada lo tiene (caso patológico que indicaría un bug de validación al guardar).
     */
    fun findByAlias(entries: List<SavedConnection>, alias: String): SavedConnection? {
        if (alias.isBlank()) return null
        val matches = entries.filter { it.alias == alias }
        return matches.singleOrNull()
    }

    /**
     * Filtra `entries` para `inventory_list`. Cada filtro es AND con los otros; dentro
     * de tags/groups el match es ANY (con que tenga uno de los listados alcanza).
     * Solo devuelve entries que tengan alias definido — sin alias no son inventory.
     */
    fun filterForInventory(
        entries: List<SavedConnection>,
        tagsAny: List<String>? = null,
        groupsAny: List<String>? = null,
        deviceType: String? = null,
    ): List<SavedConnection> = entries
        .filter { !it.alias.isNullOrBlank() }
        .filter { tagsAny.isNullOrEmpty() || it.tags.any { t -> t in tagsAny } }
        .filter { groupsAny.isNullOrEmpty() || it.groups.any { g -> g in groupsAny } }
        .filter { deviceType.isNullOrBlank() || it.deviceType.equals(deviceType, ignoreCase = true) }

    /**
     * Detecta colisiones de alias antes de persistir. La UI lo usa para impedir guardar
     * dos entries con el mismo alias.
     */
    fun aliasCollisions(entries: List<SavedConnection>): Set<String> {
        val counts = entries.mapNotNull { it.alias?.takeIf { a -> a.isNotBlank() } }
            .groupingBy { it }
            .eachCount()
        return counts.filterValues { it > 1 }.keys
    }
}
