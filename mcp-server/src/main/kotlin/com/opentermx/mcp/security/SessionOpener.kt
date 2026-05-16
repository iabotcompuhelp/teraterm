package com.opentermx.mcp.security

/**
 * Abstrae la apertura de sesiones nuevas desde MCP. Vive como interfaz acá porque el
 * `mcp-server` no puede tocar JavaFX ni los `ConnectionFactory` específicos sin acoplarse
 * fuerte a `app`. La implementación real (`com.opentermx.app.ui.mcp.JavaFxSessionOpener`)
 * en `app/` despacha al `MainWindow.openSession` saltando al FX thread y devuelve el
 * `sessionId` resultante.
 */
fun interface SessionOpener {

    fun open(request: OpenRequest): OpenResult

    object NoOp : SessionOpener {
        override fun open(request: OpenRequest): OpenResult =
            OpenResult.Failure("open_session no está habilitado: no hay UI registrada como opener")
    }
}

data class OpenRequest(
    val protocol: String,
    val host: String?,
    val port: Int?,
    val username: String?,
    val credentialRef: String?,
    val label: String?,
)

sealed interface OpenResult {
    data class Success(val sessionId: String, val label: String) : OpenResult
    data class Failure(val message: String) : OpenResult
}