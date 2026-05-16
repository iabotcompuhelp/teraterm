package com.opentermx.mcp.handlers

import com.opentermx.ai.context.Vendor
import com.opentermx.ai.context.VendorDetector
import com.opentermx.ai.safety.ClassifiedCommand
import com.opentermx.ai.safety.RiskLevel
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.session.SessionId
import com.opentermx.macro.MacroEngine
import com.opentermx.macro.MacroRegistry
import com.opentermx.macro.MacroUiBridge
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.NOT_FOUND
import com.opentermx.mcp.handlers.McpToolException.ErrorCode.UNAVAILABLE
import com.opentermx.mcp.security.ApprovalDecision
import com.opentermx.mcp.security.ApprovalGate
import com.opentermx.mcp.tools.ToolDef
import com.opentermx.mcp.tools.ToolDefinitions
import java.util.UUID

/**
 * Handler de la tool MUTATIVA `run_macro`. Flujo:
 *
 *  1. Buscar el macro en el [MacroRegistry]. Si no existe → error NOT_FOUND.
 *  2. Resolver la sesión target (o devolver UNAVAILABLE si no hay Connection registrada).
 *  3. Prefijar las parameters al script (`params.foo = ...`).
 *  4. Pedir aprobación al operador mostrando el **script completo** (líneas marcadas
 *     CONFIG en la UI — la UI las trata como un bloque a aprobar, no comandos
 *     individuales clasificables).
 *  5. Si aprueba, ejecutar via [MacroEngine.runBlocking] capturando log entries.
 *  6. Devolver el resultado con output del log y errors si hubo.
 */
class RunMacroHandler(
    private val approvalGate: ApprovalGate,
    private val registry: MacroRegistry = MacroRegistry(),
    private val engine: MacroEngine = MacroEngine(),
) : ToolHandler {

    override val definition: ToolDef = ToolDefinitions.RUN_MACRO

    override suspend fun invoke(args: Map<String, Any?>): Map<String, Any?> {
        val macroName = Args.requireString(args, "macroName")
        @Suppress("UNCHECKED_CAST")
        val params = (args["parameters"] as? Map<String, Any?>) ?: emptyMap()
        val sessionIdRaw = Args.optionalString(args, "sessionId")

        val info = registry.lookup(macroName)
            ?: throw McpToolException(NOT_FOUND, "Macro `$macroName` no registrado")
        val scriptRaw = runCatching { registry.readScript(info) }
            .getOrElse { throw McpToolException(UNAVAILABLE, "No se pudo leer macro: ${it.message}") }

        val sessionId = sessionIdRaw?.let { SessionId(it) }
        val connection = sessionId?.let { SessionRegistry.connectionOf(it) }
        if (sessionIdRaw != null && connection == null) {
            throw McpToolException(
                UNAVAILABLE,
                "Sesión `$sessionIdRaw` no tiene Connection registrada — ¿no está conectada todavía?",
            )
        }

        val sample = sessionId?.let { SessionRegistry.lastLinesOf(it, 64).joinToString("\n") }.orEmpty()
        val vendor = if (sample.isBlank()) Vendor.UNKNOWN else VendorDetector.detect(sample)
        // Marcamos cada línea del script como CONFIG en el panel de revisión: la UI
        // muestra el script tal cual y el operador puede editar/aprobar/rechazar como
        // bloque. La granularidad del riesgo a nivel línea no aplica para Groovy.
        val scriptLines = scriptRaw.lines()
        val classifications = scriptLines.map { ClassifiedCommand(it, RiskLevel.CONFIG) }
        val prompt = "Ejecutar macro `$macroName` (${params.size} parámetros)"
        val decision = approvalGate.reviewCommands(prompt, vendor, classifications)
        val executionId = UUID.randomUUID().toString()

        if (decision is ApprovalDecision.Reject) {
            return linkedMapOf(
                "approved" to false,
                "executed" to false,
                "executionId" to executionId,
                "output" to null,
                "errors" to emptyList<String>(),
            )
        }
        require(decision is ApprovalDecision.Approve)

        // Prefijo: declaramos los params en el binding del script via Groovy syntax.
        val paramsScript = buildString {
            params.forEach { (k, v) -> append("params.$k = ${renderLiteral(v)}\n") }
        }
        val finalScript = paramsScript + scriptRaw

        if (connection == null) {
            return linkedMapOf(
                "approved" to true,
                "executed" to false,
                "executionId" to executionId,
                "output" to null,
                "errors" to listOf("Sesión sin Connection; el macro no se ejecutó"),
            )
        }

        val logBuffer = mutableListOf<String>()
        val result = runCatching {
            engine.runBlocking(
                finalScript,
                connection,
                sessionId!!.value,
                MacroUiBridge.NoOp(),
                null,
            ) { entry -> logBuffer.add(entry.toString()) }
        }
        return result.fold(
            onSuccess = { r ->
                linkedMapOf<String, Any?>(
                    "approved" to true,
                    "executed" to r.success(),
                    "executionId" to executionId,
                    "output" to logBuffer.joinToString("\n"),
                    "errors" to if (r.success()) emptyList() else listOf(r.error()?.message ?: "fallo desconocido"),
                )
            },
            onFailure = { ex ->
                linkedMapOf<String, Any?>(
                    "approved" to true,
                    "executed" to false,
                    "executionId" to executionId,
                    "output" to logBuffer.joinToString("\n"),
                    "errors" to listOf(ex.message ?: ex.javaClass.simpleName),
                )
            },
        )
    }

    private fun renderLiteral(v: Any?): String = when (v) {
        null -> "null"
        is Number, is Boolean -> v.toString()
        is String -> "'" + v.replace("\\", "\\\\").replace("'", "\\'") + "'"
        else -> "'" + v.toString().replace("'", "\\'") + "'"
    }
}