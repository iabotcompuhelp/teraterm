package com.opentermx.app.ui.ai

import com.opentermx.app.settings.AiAssistantSettings
import com.opentermx.macro.AiExecuteResult
import com.opentermx.macro.MacroAiBridge

/**
 * Bridge IA sin operador (REST API). `ai_ask` funciona normalmente porque sólo necesita
 * llamar al LLM; `ai_execute` lanza [UnsupportedOperationException] — inyectar comandos
 * en un dispositivo de red sin aprobación humana es contrario al principio de seguridad
 * fundamental de la spec v4 ("La IA NUNCA ejecuta comandos directamente en el dispositivo").
 */
class HeadlessMacroAiBridge(
    private val settingsProvider: () -> AiAssistantSettings,
) : MacroAiBridge {

    override fun ask(prompt: String, sessionId: String?): String {
        val settings = settingsProvider()
        require(settings.isConfigured()) { "AI not configured" }
        return AiInvoker.invoke(settings, prompt, sessionId).response.text
    }

    override fun execute(prompt: String, sessionId: String?): AiExecuteResult {
        throw UnsupportedOperationException(
            "ai_execute requiere aprobación de un operador. En entornos REST/headless usa " +
                "ai_ask para inspeccionar la respuesta y decide tu lógica desde el macro."
        )
    }
}
