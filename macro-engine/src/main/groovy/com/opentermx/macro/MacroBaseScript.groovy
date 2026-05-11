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

    void tftp_put(String host, int port, String remoteFile, String localFile) {
        ctx.tftpPut(host, port, remoteFile, localFile)
    }

    void tftp_put(String host, String remoteFile, String localFile) {
        ctx.tftpPut(host, 69, remoteFile, localFile)
    }

    void tftp_get(String host, int port, String remoteFile, String localFile) {
        ctx.tftpGet(host, port, remoteFile, localFile)
    }

    void tftp_get(String host, String remoteFile, String localFile) {
        ctx.tftpGet(host, 69, remoteFile, localFile)
    }

    void tftp_server_start(int port, String rootDir) {
        ctx.tftpServerStart(port, rootDir, true, true)
    }

    void tftp_server_start(int port, String rootDir, boolean allowGet, boolean allowPut) {
        ctx.tftpServerStart(port, rootDir, allowGet, allowPut)
    }

    void tftp_server_stop() {
        ctx.tftpServerStop()
    }

    // ---------- IA (spec v4 § Comandos IA para macros, línea 185) ----------
    // ai_ask: envía un prompt al LLM configurado y devuelve la respuesta como String.
    // ai_execute: pide a la IA comandos CLI, los muestra al operador para revisión, y al
    // aprobar los inyecta por la conexión activa. Devuelve un AiExecuteResult.

    String ai_ask(String prompt) {
        ctx.aiAsk(prompt)
    }

    AiExecuteResult ai_execute(String prompt) {
        ctx.aiExecute(prompt)
    }
}