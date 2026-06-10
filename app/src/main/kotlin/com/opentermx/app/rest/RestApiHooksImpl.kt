package com.opentermx.app.rest

import com.opentermx.app.ui.tftp.TftpServerManager
import com.opentermx.app.viewmodel.TerminalSessionController
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.macro.MacroAiBridge
import com.opentermx.macro.MacroEngine
import com.opentermx.macro.MacroLogEntry
import com.opentermx.macro.MacroUiBridge
import com.opentermx.rest.ExecuteMacroRequest
import com.opentermx.rest.ExecuteMacroResponse
import com.opentermx.rest.RestApiHooks
import com.opentermx.rest.SendTerminalResponse
import com.opentermx.rest.SessionInfo
import com.opentermx.rest.TerminalBufferResponse
import com.opentermx.rest.TftpStartRequest
import com.opentermx.rest.TftpStatusResponse
import com.opentermx.tftp.server.TftpServerConfig
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory

/**
 * Implementación de [RestApiHooks] que vive en el módulo `app` y conoce las dependencias
 * runtime: el mapa de [TerminalSessionController] activos (para resolver `sessionId` a
 * `Connection`), [SessionRegistry] (buffer + metadata), [TftpServerManager] (start/stop
 * del servidor embebido) y [MacroEngine] (ejecución de scripts Groovy).
 *
 * El bridge UI inyectado para macros REST es [HeadlessMacroUiBridge] — los `messagebox`
 * y `inputbox` del script no pueden ser interactivos desde una petición externa, así que
 * se loguean y devuelven defaults sin bloquear.
 */
class RestApiHooksImpl(
    private val findControllerById: (String) -> TerminalSessionController?,
    private val getTftpDefaultCsvPath: () -> String,
    private val aiBridge: MacroAiBridge = MacroAiBridge.NoOp(),
) : RestApiHooks {

    private val log = LoggerFactory.getLogger(javaClass)
    private val macroEngine = MacroEngine()

    override fun executeMacro(request: ExecuteMacroRequest): ExecuteMacroResponse {
        val started = System.currentTimeMillis()
        val controller = findControllerById(request.sessionId)
            ?: return ExecuteMacroResponse(
                status = "error",
                durationMs = System.currentTimeMillis() - started,
                log = emptyList(),
                error = "Sesión no encontrada: ${request.sessionId}",
            )
        val script = resolveScript(request)
            ?: return ExecuteMacroResponse(
                status = "error",
                durationMs = System.currentTimeMillis() - started,
                log = emptyList(),
                error = "Debes pasar 'script' o 'scriptPath'",
            )
        val collectedLog = mutableListOf<String>()
        val onLog: (MacroLogEntry) -> Unit = { e ->
            collectedLog += "+${e.elapsedMillis()}ms ${e.message()}"
        }
        return try {
            val exec = macroEngine.start(script, controller.session.connection, request.sessionId, HeadlessMacroUiBridge, aiBridge, onLog)
            val timeoutMs = (request.timeoutSeconds.coerceAtLeast(1)).toLong() * 1000L
            val finished = exec.await(timeoutMs)
            val durationMs = System.currentTimeMillis() - started
            if (!finished) {
                exec.cancel()
                ExecuteMacroResponse("timeout", durationMs, collectedLog.toList(), "Timeout tras ${request.timeoutSeconds}s")
            } else {
                val result = exec.getResult()
                val errMsg = result.error()?.let { "${it.javaClass.simpleName}: ${it.message}" }
                ExecuteMacroResponse(
                    status = if (result.success()) "ok" else "error",
                    durationMs = durationMs,
                    log = collectedLog.toList(),
                    error = errMsg,
                )
            }
        } catch (t: Throwable) {
            log.warn("Macro REST falló", t)
            ExecuteMacroResponse(
                "error",
                System.currentTimeMillis() - started,
                collectedLog.toList(),
                "${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }

    override fun sendToTerminal(sessionId: String, text: String): SendTerminalResponse {
        val sid = com.opentermx.common.session.SessionId(sessionId)
        val sink = SessionRegistry.sinkOf(sid)
            ?: return SendTerminalResponse("error", 0, "Sesión no encontrada o sin sink: $sessionId")
        val ok = runCatching { sink.sendLine(text) }.getOrDefault(false)
        return if (ok) SendTerminalResponse("ok", text.toByteArray(Charsets.UTF_8).size)
        else SendTerminalResponse("error", 0, "Sesión no conectada")
    }

    override fun terminalBuffer(sessionId: String, lines: Int): TerminalBufferResponse {
        val sid = com.opentermx.common.session.SessionId(sessionId)
        val captured = SessionRegistry.lastLinesOf(sid, lines.coerceIn(1, 500))
        return TerminalBufferResponse(sessionId, captured)
    }

    override fun listSessions(): List<SessionInfo> =
        SessionRegistry.activeSessions().map { d ->
            SessionInfo(
                id = d.id.value,
                name = d.metadata.name,
                protocol = d.metadata.protocol,
                host = d.metadata.host,
                port = d.metadata.port,
                username = d.metadata.username,
            )
        }

    override fun startTftpServer(request: TftpStartRequest): TftpStatusResponse {
        val root = Path.of(request.rootDir)
        if (!Files.isDirectory(root)) {
            return TftpStatusResponse(running = false, error = "rootDir no es un directorio: ${request.rootDir}")
        }
        val cfg = TftpServerConfig(request.port, root, request.allowGet, request.allowPut, 5, 5)
        return TftpServerManager.start(cfg, getTftpDefaultCsvPath()).fold(
            onSuccess = { port -> TftpStatusResponse(running = true, port = port, rootDir = request.rootDir) },
            onFailure = { e -> TftpStatusResponse(running = false, error = e.message ?: e.javaClass.simpleName) },
        )
    }

    override fun stopTftpServer(): TftpStatusResponse {
        TftpServerManager.stop()
        return TftpStatusResponse(running = false)
    }

    override fun tftpStatus(): TftpStatusResponse {
        // snapshot() para que running/port/rootDir salgan del mismo estado y no de
        // tres lecturas que un stop() concurrente puede partir al medio.
        val snap = TftpServerManager.snapshot()
        return TftpStatusResponse(
            running = snap.running,
            port = if (snap.running) snap.port else null,
            rootDir = snap.config?.rootDirectory()?.toString(),
        )
    }

    private fun resolveScript(req: ExecuteMacroRequest): String? {
        if (!req.script.isNullOrBlank()) return req.script
        val path = req.scriptPath?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Files.readString(Path.of(path), Charsets.UTF_8) }.getOrNull()
    }
}

/**
 * Bridge para macros invocados vía REST: no abre diálogos JavaFX (no hay operador
 * delante). Loguea las llamadas a `messagebox`/`inputbox` y devuelve respuestas seguras.
 */
object HeadlessMacroUiBridge : MacroUiBridge {
    private val log = LoggerFactory.getLogger(javaClass)
    override fun showMessage(message: String) {
        log.info("[macro:rest] messagebox: {}", message)
    }
    override fun prompt(message: String, defaultValue: String): String {
        log.info("[macro:rest] inputbox: {} (default={})", message, defaultValue)
        return defaultValue
    }
    override fun getClipboard(): String = ""
    override fun setClipboard(text: String) {}
}
