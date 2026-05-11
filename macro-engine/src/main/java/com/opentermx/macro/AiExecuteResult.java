package com.opentermx.macro;

import java.util.List;

/**
 * Resultado de {@link MacroAiBridge#execute(String, String)}: qué comandos generó
 * la IA, qué decidió el operador y cuántos llegaron al dispositivo.
 */
public record AiExecuteResult(
        Outcome outcome,
        List<String> commands,
        int executedCount,
        int failedCount,
        String error
) {

    public boolean approved() {
        return outcome == Outcome.APPROVED || outcome == Outcome.PARTIAL;
    }

    public boolean isRejected() {
        return outcome == Outcome.REJECTED;
    }

    public enum Outcome {
        /** El operador aprobó todos los comandos generados. */
        APPROVED,
        /** El operador aprobó un subconjunto (vía "Ejecutar seleccionados" o edición). */
        PARTIAL,
        /** El operador rechazó el bloque entero. */
        REJECTED,
        /** El LLM no devolvió bloques de código ejecutables. */
        NO_COMMANDS,
        /** Falló la llamada al LLM o la conexión al dispositivo. */
        ERROR
    }

    public static AiExecuteResult rejected(List<String> commands) {
        return new AiExecuteResult(Outcome.REJECTED, commands, 0, 0, null);
    }

    public static AiExecuteResult noCommands(String responseText) {
        return new AiExecuteResult(Outcome.NO_COMMANDS, List.of(), 0, 0,
                responseText.isBlank() ? null : responseText);
    }

    public static AiExecuteResult error(String message) {
        return new AiExecuteResult(Outcome.ERROR, List.of(), 0, 0, message);
    }
}
