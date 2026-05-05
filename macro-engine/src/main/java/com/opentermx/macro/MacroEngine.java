package com.opentermx.macro;

import com.opentermx.common.connection.Connection;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public final class MacroEngine {

    private static final Logger log = LoggerFactory.getLogger(MacroEngine.class);
    private static final String BASE_SCRIPT_CLASS = "com.opentermx.macro.MacroBaseScript";

    public MacroExecution start(
            String script,
            Connection connection,
            String sessionId,
            MacroUiBridge ui,
            Consumer<MacroLogEntry> onLog
    ) {
        MacroContext ctx = new MacroContext(connection, sessionId, ui, onLog);
        ctx.start();

        Thread[] threadHolder = new Thread[1];
        MacroExecution[] execHolder = new MacroExecution[1];

        Runnable task = () -> {
            try {
                CompilerConfiguration cc = new CompilerConfiguration();
                cc.setScriptBaseClass(BASE_SCRIPT_CLASS);
                Binding binding = new Binding();
                binding.setVariable("_ctx", ctx);
                GroovyShell shell = new GroovyShell(MacroEngine.class.getClassLoader(), binding, cc);
                shell.evaluate(script);
                execHolder[0].setResult(new MacroResult(true, null, ctx.getLogEntries()));
            } catch (Throwable t) {
                if (t instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("Macro ejecución falló", t);
                execHolder[0].setResult(new MacroResult(false, t, ctx.getLogEntries()));
            } finally {
                ctx.stop();
            }
        };

        threadHolder[0] = new Thread(task, "macro-runner");
        threadHolder[0].setDaemon(true);
        execHolder[0] = new MacroExecution(threadHolder[0], ctx);
        threadHolder[0].start();
        return execHolder[0];
    }

    public MacroResult runBlocking(String script, Connection connection, String sessionId, MacroUiBridge ui) throws InterruptedException {
        MacroExecution exec = start(script, connection, sessionId, ui, e -> {});
        exec.await();
        return exec.getResult();
    }
}