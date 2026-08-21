package com.opentermx.app.ui.ai

import com.opentermx.agent.RemoteCommandTask
import com.opentermx.agent.RemoteTaskProcessor
import com.opentermx.agent.RemoteTaskResult
import com.opentermx.agent.RemoteTaskStatus
import com.opentermx.ai.context.Vendor
import com.opentermx.ai.context.VendorDetector
import com.opentermx.ai.safety.CredentialRedactor
import com.opentermx.ai.safety.RiskClassifier
import com.opentermx.common.ai.SessionRegistry
import com.opentermx.common.session.SessionId
import com.opentermx.mcp.security.ApprovalDecision
import com.opentermx.mcp.security.ApprovalGate
import com.opentermx.mcp.exec.SessionCommandRunner
import kotlinx.coroutines.runBlocking

class ApprovedRemoteTaskProcessor(
    private val approvalGate: ApprovalGate,
    private val clock: () -> Long = System::currentTimeMillis,
    private val commandRunner: SessionCommandRunner = SessionCommandRunner(),
    private val commandTimeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MILLIS,
) : RemoteTaskProcessor {
    private val redactor = CredentialRedactor()

    override fun process(task: RemoteCommandTask): RemoteTaskResult = runBlocking {
        if (task.expiresAtMillis <= clock()) return@runBlocking failed(task, "La tarea expiró antes de aprobarse")
        val sessionId = SessionId(task.sessionId)
        val metadata = SessionRegistry.metadataOf(sessionId)
            ?: return@runBlocking failed(task, "La sesión ya no está disponible")
        val sink = SessionRegistry.sinkOf(sessionId)
            ?: return@runBlocking failed(task, "La sesión no admite inyección de comandos")
        val sample = SessionRegistry.lastLinesOf(sessionId, 64).joinToString("\n")
        val vendor = if (sample.isBlank()) Vendor.UNKNOWN else VendorDetector.detect(sample)
        val classified = RiskClassifier.classify(task.commands, vendor)
        val prompt = "Tarea remota ${task.taskId}: ${task.rationale}"

        when (val decision = approvalGate.reviewCommands(prompt, vendor, classified)) {
            ApprovalDecision.Reject -> RemoteTaskResult(
                taskId = task.taskId,
                status = RemoteTaskStatus.REJECTED,
                completedAtMillis = clock(),
                error = "Rechazada por el operador",
            )
            is ApprovalDecision.Approve -> {
                val executed = mutableListOf<String>()
                val outputs = mutableListOf<String>()
                var failure: String? = null
                for (command in decision.commands) {
                    val result = runCatching {
                        commandRunner.run(
                            sessionId = sessionId,
                            vendor = vendor,
                            command = command,
                            timeoutMillis = commandTimeoutMillis,
                            depaginate = false,
                        )
                    }.getOrElse { error ->
                        failure = error.message ?: "No se pudo ejecutar el comando"
                        break
                    }
                    executed += command
                    outputs += buildString {
                        append("$ ").append(command)
                        if (result.output.isNotBlank()) append('\n').append(result.output)
                    }
                    if (result.timedOut) {
                        failure = "Tiempo de espera agotado ejecutando $command"
                        break
                    }
                }
                RemoteTaskResult(
                    taskId = task.taskId,
                    status = if (failure == null) RemoteTaskStatus.SUCCEEDED else RemoteTaskStatus.FAILED,
                    completedAtMillis = clock(),
                    executedCommands = executed,
                    output = redactor.redact(outputs.joinToString("\n\n"), vendor),
                    error = failure,
                )
            }
        }
    }

    private fun failed(task: RemoteCommandTask, message: String) = RemoteTaskResult(
        taskId = task.taskId,
        status = RemoteTaskStatus.FAILED,
        completedAtMillis = clock(),
        error = message,
    )

    private companion object {
        const val DEFAULT_COMMAND_TIMEOUT_MILLIS = 30_000L
    }
}
