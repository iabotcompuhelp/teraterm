package com.opentermx.macro;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica que los comandos {@code ai_ask} / {@code ai_execute} del MacroBaseScript
 * delegan correctamente al {@link MacroAiBridge} inyectado y propagan el resultado.
 */
class MacroAiCommandsTest {

    @Test
    void aiAskReturnsBridgeResponse() throws Exception {
        StubBridge bridge = new StubBridge();
        bridge.askResponse = "Cisco IOS detectado";
        AtomicReference<String> capturedLog = new AtomicReference<>();
        MacroResult result = new MacroEngine().runBlocking(
                "def r = ai_ask('detecta vendor'); log('respuesta: ' + r); r",
                null, "sess-1", noopUi(), bridge, e -> capturedLog.set(e.message())
        );
        assertTrue(result.success(), "macro should succeed; error=" + result.error());
        assertEquals("detecta vendor", bridge.lastAskPrompt);
        assertEquals("sess-1", bridge.lastAskSession);
    }

    @Test
    void aiExecuteReturnsBridgeOutcome() throws Exception {
        StubBridge bridge = new StubBridge();
        bridge.executeResult = new AiExecuteResult(
                AiExecuteResult.Outcome.APPROVED,
                List.of("show ver"), 1, 0, null);
        MacroResult result = new MacroEngine().runBlocking(
                "def r = ai_execute('show version'); log('outcome=' + r.outcome() + ' exec=' + r.executedCount())",
                null, "sess-1", noopUi(), bridge, e -> {}
        );
        assertTrue(result.success(), "macro should succeed; error=" + result.error());
        assertEquals("show version", bridge.lastExecutePrompt);
    }

    @Test
    void aiAskFailsWithoutBridge() throws Exception {
        MacroResult result = new MacroEngine().runBlocking(
                "ai_ask('hola')",
                null, "sess-1", noopUi(), null, e -> {}
        );
        assertFalse(result.success());
        assertNotNull(result.error());
        assertTrue(result.error().getMessage().contains("Setup → AI Assistant"),
                "Expected helpful error message, got: " + result.error().getMessage());
    }

    @Test
    void aiExecuteFailsWithoutBridge() throws Exception {
        MacroResult result = new MacroEngine().runBlocking(
                "ai_execute('crea vlan 30')",
                null, "sess-1", noopUi(), null, e -> {}
        );
        assertFalse(result.success());
        assertTrue(result.error().getMessage().contains("Setup → AI Assistant"));
    }

    private static MacroUiBridge noopUi() {
        return new MacroUiBridge.NoOp();
    }

    static class StubBridge implements MacroAiBridge {
        String askResponse = "";
        AiExecuteResult executeResult = new AiExecuteResult(AiExecuteResult.Outcome.REJECTED, List.of(), 0, 0, null);
        String lastAskPrompt;
        String lastAskSession;
        String lastExecutePrompt;

        @Override public String ask(String prompt, String sessionId) {
            lastAskPrompt = prompt; lastAskSession = sessionId; return askResponse;
        }
        @Override public AiExecuteResult execute(String prompt, String sessionId) {
            lastExecutePrompt = prompt; return executeResult;
        }
    }
}
