package com.opentermx.macro

abstract class MacroBaseScript extends Script {

    // Must be at least package-private: Groovy 4 Indy resolves `ctx` as a property
    // by looking up the getter, and a `private` getter is not visible to the
    // dynamically generated Script subclass.
    protected MacroContext getCtx() {
        def c = binding.getVariable("_ctx")
        if (c == null) throw new MacroException("MacroContext no inyectado en el binding")
        return c as MacroContext
    }

    void send(String text) {
        ctx.send(text)
    }

    void sendln(String text) {
        ctx.sendln(text)
    }

    boolean waitfor(String pattern) {
        ctx.waitfor(pattern, 30_000L)
    }

    boolean waitfor(String pattern, long timeoutSeconds) {
        ctx.waitfor(pattern, timeoutSeconds * 1000L)
    }

    // Groovy translates `waitfor("foo", timeout: 5)` into `waitfor([timeout:5], "foo")`
    // — named arguments are collected into a Map that becomes the FIRST positional arg.
    boolean waitfor(Map opts, String pattern) {
        long seconds = (opts.timeout != null ? ((Number) opts.timeout).longValue() : 30L)
        ctx.waitfor(pattern, seconds * 1000L)
    }

    void pause(long millis) {
        ctx.pause(millis)
    }

    void messagebox(String message) {
        ctx.messagebox(message)
    }

    String inputbox(String prompt) {
        ctx.inputbox(prompt, "")
    }

    String inputbox(String prompt, String defaultValue) {
        ctx.inputbox(prompt, defaultValue)
    }

    void filelog(String path) {
        ctx.filelog(path)
    }

    void log(String message) {
        ctx.log(message)
    }

    String getClipboard() {
        ctx.getClipboard()
    }

    void setClipboard(String text) {
        ctx.setClipboard(text)
    }
}