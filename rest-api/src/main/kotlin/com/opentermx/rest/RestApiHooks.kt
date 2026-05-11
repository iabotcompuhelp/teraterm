package com.opentermx.rest

/**
 * Contrato que la aplicación implementa para exponer los endpoints REST a procesos
 * externos (n8n, Node-RED, SIEM, Zabbix, CI/CD…). El servidor REST [RestApiServer]
 * sólo conoce este contrato — la implementación concreta vive en el módulo `app`
 * y cablea `MacroEngine` + `SessionRegistry` + `TftpServerManager` + audit log IA.
 */
interface RestApiHooks {

    fun executeMacro(request: ExecuteMacroRequest): ExecuteMacroResponse

    fun sendToTerminal(sessionId: String, text: String): SendTerminalResponse

    fun terminalBuffer(sessionId: String, lines: Int): TerminalBufferResponse

    fun listSessions(): List<SessionInfo>

    fun startTftpServer(request: TftpStartRequest): TftpStatusResponse

    fun stopTftpServer(): TftpStatusResponse

    fun tftpStatus(): TftpStatusResponse
}

// ---------- DTOs ----------

data class ExecuteMacroRequest(
    val script: String? = null,
    val scriptPath: String? = null,
    val sessionId: String,
    val timeoutSeconds: Int = 60,
)

data class ExecuteMacroResponse(
    val status: String,
    val durationMs: Long,
    val log: List<String>,
    val error: String? = null,
)

data class SendTerminalResponse(
    val status: String,
    val bytesSent: Int,
    val error: String? = null,
)

data class TerminalBufferResponse(
    val sessionId: String,
    val lines: List<String>,
)

data class SessionInfo(
    val id: String,
    val name: String,
    val protocol: String,
    val host: String?,
    val port: Int?,
    val username: String?,
)

data class TftpStartRequest(
    val port: Int = 69,
    val rootDir: String,
    val allowGet: Boolean = true,
    val allowPut: Boolean = true,
)

data class TftpStatusResponse(
    val running: Boolean,
    val port: Int? = null,
    val rootDir: String? = null,
    val error: String? = null,
)
