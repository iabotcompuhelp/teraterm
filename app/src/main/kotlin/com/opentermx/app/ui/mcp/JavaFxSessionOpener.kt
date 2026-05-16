package com.opentermx.app.ui.mcp

import com.opentermx.mcp.security.OpenRequest
import com.opentermx.mcp.security.OpenResult
import com.opentermx.mcp.security.SessionOpener

/**
 * Implementación JavaFX de [SessionOpener]. Por ahora devuelve `Failure` con un mensaje
 * pedagógico — la integración real requiere refactorizar `MainWindow.openSession(...)`
 * para que sea invocable desde fuera de FX y conozca cómo resolver `credentialRef` al
 * keychain.
 *
 * Para que la tool `open_session` sea funcional end-to-end, faltan dos piezas que están
 * fuera del scope de phase 2:
 *  1. Un keychain real con resolución por handle (`credentialRef`).
 *  2. Una API pública en `MainWindow` (o en un `SessionService` extraído) para abrir
 *     sesiones programáticamente sin pasar por el diálogo "New Connection".
 *
 * Mientras tanto, el handler devuelve un mensaje claro al cliente MCP indicando esto.
 */
object JavaFxSessionOpener : SessionOpener {

    override fun open(request: OpenRequest): OpenResult {
        return OpenResult.Failure(
            "open_session vía MCP todavía no resuelve credenciales del keychain. " +
                "Abrí la sesión desde Setup → New Connection y reintentá con esa sesión activa.",
        )
    }
}