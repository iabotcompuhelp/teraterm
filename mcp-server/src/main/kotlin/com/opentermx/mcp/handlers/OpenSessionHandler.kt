package com.opentermx.mcp.handlers

import com.opentermx.ai.context.Vendor
import com.opentermx.ai.safety.ClassifiedCommand
import com.opentermx.ai.safety.RiskLevel
import com.opentermx.mcp.security.ApprovalDecision
import com.opentermx.mcp.security.ApprovalGate
import com.opentermx.mcp.security.OpenRequest
import com.opentermx.mcp.security.OpenResult
import com.opentermx.mcp.security.SessionOpener
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions

/**
 * Handler de la tool MUTATIVA `open_session`. Pide al operador autorizar la apertura de
 * la sesión antes de invocar al [SessionOpener] (que vive en `app/` y conoce los
 * `ConnectionFactory` específicos).
 *
 * El operador ve un resumen del destino (protocolo, host, puerto, usuario) — passwords
 * nunca cruzan MCP. Si el cliente quiere reusar credenciales, debe pasarlas por
 * `credentialRef` (handle al keychain interno).
 */
class OpenSessionHandler(
    private val approvalGate: ApprovalGate,
    private val opener: SessionOpener,
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.OPEN_SESSION

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val protocol = Args.requireString(args, "protocol")
        val host = Args.optionalString(args, "host")
        val port = (args["port"] as? Number)?.toInt()
        val username = Args.optionalString(args, "username")
        val credentialRef = Args.optionalString(args, "credentialRef")
        val label = Args.optionalString(args, "label")
            ?: "${protocol.lowercase()}://${host ?: ""}${port?.let { ":$it" } ?: ""}"

        // Mostramos al operador una descripción humana del destino — cada línea es una
        // "command-like" entrada para que el panel de aprobación las visualice como bloque.
        val summary = listOfNotNull(
            "open_session: $protocol",
            host?.let { "host: $it" },
            port?.let { "port: $it" },
            username?.let { "user: $it" },
            credentialRef?.let { "credentials: $it (keychain)" },
            "label: $label",
        )
        val classifications = summary.map { ClassifiedCommand(it, RiskLevel.CONFIG) }
        val decision = approvalGate.reviewCommands(
            prompt = "Solicitud MCP de abrir sesión a $label",
            vendor = Vendor.UNKNOWN,
            classifications = classifications,
        )

        if (decision is ApprovalDecision.Reject) {
            return linkedMapOf(
                "approved" to false,
                "sessionId" to null,
                "error" to "operador rechazó la apertura",
            )
        }
        val request = OpenRequest(protocol, host, port, username, credentialRef, label)
        return when (val result = opener.open(request)) {
            is OpenResult.Success -> linkedMapOf(
                "approved" to true,
                "sessionId" to result.sessionId,
                "error" to null,
            )
            is OpenResult.Failure -> linkedMapOf(
                "approved" to true,
                "sessionId" to null,
                "error" to result.message,
            )
        }
    }
}