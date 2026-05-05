package com.opentermx.macro

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class MacroEngineTest {

    private final MacroEngine engine = new MacroEngine()

    @Test
    void runsSimpleScript() {
        def result = engine.runBlocking('log "step1"; pause 10; log "step2"', null, null, new MacroUiBridge.NoOp())
        assertTrue(result.success())
        assertNull(result.error())
        def messages = result.log().collect { it.message() }
        assertTrue(messages.any { it.contains("step1") })
        assertTrue(messages.any { it.contains("step2") })
    }

    @Test
    void reportsScriptError() {
        def result = engine.runBlocking('throw new RuntimeException("boom")', null, null, new MacroUiBridge.NoOp())
        assertFalse(result.success())
        assertNotNull(result.error())
        assertEquals("boom", result.error().message)
    }

    @Test
    void messageboxAndInputboxRoundtrip() {
        def captured = []
        def bridge = new MacroUiBridge() {
            @Override void showMessage(String message) { captured << ("msg:" + message) }
            @Override String prompt(String message, String defaultValue) { return "user-said-" + defaultValue }
            @Override String getClipboard() { return "" }
            @Override void setClipboard(String text) {}
        }
        def script = '''
            messagebox "hola"
            def name = inputbox("nombre?", "world")
            log "got=" + name
        '''
        def result = engine.runBlocking(script, null, null, bridge)
        assertTrue(result.success())
        assertTrue(captured.contains("msg:hola"))
        assertTrue(result.log().any { it.message().contains("got=user-said-world") })
    }

    @Test
    void waitforReturnsFalseWithoutMatch() {
        def script = 'def found = waitfor("nunca", timeout: 0); log "found=" + found'
        def result = engine.runBlocking(script, null, null, new MacroUiBridge.NoOp())
        assertTrue(result.success())
        assertTrue(result.log().any { it.message().contains("found=false") })
    }
}