package com.opentermx.macro;

import java.util.List;

/**
 * Puente entre {@link MacroContext} y el subsistema IA. La implementación concreta
 * vive en el módulo {@code app} (con UI de aprobación + audit log) o en {@code rest-api}
 * (headless: ai_ask sí, ai_execute lanza error).
 *
 * Pensado para que los macros puedan delegar generación de comandos al LLM configurado
 * sin acoplar {@code macro-engine} a {@code ai-assistant} (mantenemos la separación de
 * responsabilidades en módulos).
 *
 * <h3>Comportamiento esperado</h3>
 * <ul>
 *   <li>{@link #ask}: envía el prompt al provider configurado y devuelve la respuesta
 *       como texto. El system prompt y el contexto del terminal los resuelve la
 *       implementación a partir de la sesión activa.</li>
 *   <li>{@link #execute}: genera comandos con IA, parsea bloques de código, muestra
 *       al operador el panel de revisión con semáforo de riesgo, y al aprobar los
 *       inyecta línea por línea por la conexión activa. Devuelve el resultado
 *       agregado. En entornos sin operador (REST) debe lanzar
 *       {@link UnsupportedOperationException}.</li>
 * </ul>
 */
public interface MacroAiBridge {

    /** Envía el prompt al LLM configurado y devuelve el texto plano de la respuesta. */
    String ask(String prompt, String sessionId) throws Exception;

    /**
     * Pide a la IA comandos CLI, los muestra al operador para revisión y, si aprueba,
     * los ejecuta sobre la sesión activa. El resultado incluye contadores y el log.
     */
    AiExecuteResult execute(String prompt, String sessionId) throws Exception;

    /** Bridge inerte usado como fallback cuando ninguna implementación está cableada. */
    final class NoOp implements MacroAiBridge {
        @Override public String ask(String prompt, String sessionId) {
            throw new IllegalStateException(
                    "ai_ask no disponible: configura el asistente IA en Setup → AI Assistant…");
        }
        @Override public AiExecuteResult execute(String prompt, String sessionId) {
            throw new IllegalStateException(
                    "ai_execute no disponible: configura el asistente IA en Setup → AI Assistant…");
        }
    }
}
